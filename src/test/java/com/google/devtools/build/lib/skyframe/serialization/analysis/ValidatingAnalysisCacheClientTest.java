// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.devtools.build.lib.actions.FileStateValue.NONEXISTENT_FILE_STATE_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_ANALYSIS_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_EMPTY;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_EXECUTION_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_FILE;
import static com.google.devtools.build.lib.skyframe.serialization.proto.DataType.DATA_TYPE_LISTING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.DirectoryListingKey;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.compress.CompressionServiceImpl;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutorOwner;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.InMemoryFingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.RepositoryBoundary;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileDataInfoOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureFileDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureListingDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureNodeDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingDataInfoOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.NodeDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.NodeDataInfoOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.NodeInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient.DepotChange;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient.ValidationStats;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.NodeEntry;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Round trips dependencies through {@link FileDependencySerializer} and checks them back with
 * {@link ValidatingAnalysisCacheClient}, the way an upload and a later download would.
 */
@RunWith(JUnit4.class)
public final class ValidatingAnalysisCacheClientTest {
  private static final int WRITER_VERSION = 100;
  private static final CompressionService COMPRESSION_SERVICE = new CompressionServiceImpl();
  private static final byte[] VALUE = "the value".getBytes(UTF_8);

  private final InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path workspace = fs.getPath("/ws");
  private final Root workspaceRoot = Root.fromPath(workspace);
  private final Path externalRoot = fs.getPath("/ob/external");

  private final InMemoryGraph graph = InMemoryGraph.create();
  private final InMemoryFingerprintValueStore store = new InMemoryFingerprintValueStore();
  private final FingerprintValueService service = FingerprintValueService.createForTesting(store);
  private final SafeExecutorOwner executor = new SafeExecutorOwner(newDirectExecutorService());
  private final ValidatingAnalysisCacheClient.Storage storage =
      new ValidatingAnalysisCacheClient.Storage(
          store, executor, COMPRESSION_SERVICE, FingerprintValueService.NONPROD_FINGERPRINTER);
  private final HashMap<Path, Long> versions = new HashMap<>();
  private final FileDependencySerializer serializer =
      new FileDependencySerializer(
          new TableVersionGetter(),
          graph,
          COMPRESSION_SERVICE,
          service,
          executor,
          /* profileCollector= */ null,
          new RepositoryBoundary(workspace, externalRoot));

  private int nextEntry = 0;

  @Test
  public void fileDependency_hitsUnlessTheFileChangedAfterTheWriter() throws Exception {
    FileKey build = workspaceFile("pkg/BUILD", 90);
    ByteString entry = putEntry(DATA_TYPE_FILE, fileInfo(build).cacheKey());

    assertHit(entry, ImmutableList.of(), ImmutableList.of());
    // Changes up to the writer's version are already reflected in the entry.
    assertHit(entry, ImmutableList.of(new DepotChange("pkg/BUILD", 95)), ImmutableList.of());
    assertHit(entry, ImmutableList.of(new DepotChange("other/BUILD", 101)), ImmutableList.of());
    assertInvalidated(
        entry, ImmutableList.of(new DepotChange("pkg/BUILD", 101)), ImmutableList.of());
    assertInvalidated(entry, ImmutableList.of(), ImmutableList.of("pkg/BUILD"));
  }

  @Test
  public void pendingChanges_lookupsWaitForThem() throws Exception {
    FileKey build = workspaceFile("pkg/BUILD", 90);
    ByteString entry = putEntry(DATA_TYPE_FILE, fileInfo(build).cacheKey());
    SettableFuture<ImmutableList<String>> unchanged = SettableFuture.create();
    SettableFuture<ImmutableList<String>> changed = SettableFuture.create();
    var hitting =
        ValidatingAnalysisCacheClient.create(storage, WRITER_VERSION, unchanged);
    var missing =
        ValidatingAnalysisCacheClient.create(storage, WRITER_VERSION, changed);

    ListenableFuture<LookupResult> hit = hitting.lookup(entry.toByteArray());
    ListenableFuture<LookupResult> miss = missing.lookup(entry.toByteArray());
    assertThat(hit.isDone()).isFalse();
    assertThat(miss.isDone()).isFalse();
    unchanged.set(ImmutableList.of("other/BUILD"));
    changed.set(ImmutableList.of("pkg/BUILD"));

    assertThat(hit.get().value()).isEqualTo(VALUE);
    assertThat(miss.get().value()).isEmpty();
  }

  @Test
  public void failedChanges_everyEntryIsAMiss() throws Exception {
    ByteString entry = putEntry(DATA_TYPE_EMPTY, /* invalidationKey= */ null);
    var client =
        ValidatingAnalysisCacheClient.create(
            storage,
            WRITER_VERSION,
            immediateFailedFuture(new IOException("not a git repository")));

    assertThat(lookup(client, entry)).isEmpty();
    assertThat(client.getValidationStats()).isEqualTo(new ValidationStats(0, 0, 1));
  }

  @Test
  public void topLevelTargets_withoutALookup_match() throws Exception {
    var client = ValidatingAnalysisCacheClient.create(
            storage, WRITER_VERSION, ImmutableList.of(), ImmutableList.of());

    var result = client.lookupTopLevelTargets(WRITER_VERSION, "config", true, "bazel");

    assertThat(result.status())
        .isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH.getNumber());
    assertThat(client.getStats().matchStatus()).isEqualTo(result.status());
  }

  @Test
  public void topLevelTargets_reportTheLookupsAnswer() throws Exception {
    var client = ValidatingAnalysisCacheClient.create(
            storage, WRITER_VERSION, ImmutableList.of(), ImmutableList.of());
    client.setTopLevelTargetsLookup(
        (version, config, fakeStamp, bazelVersion) ->
            new LookupTopLevelTargetsResultImpl(
                TopLevelTargetsMatchStatus.MATCH_STATUS_DIFFERENT_CL.getNumber(),
                version + " " + config));

    var result = client.lookupTopLevelTargets(WRITER_VERSION, "config", true, "bazel");

    assertThat(result.statusMessage()).isEqualTo(WRITER_VERSION + " config");
    assertThat(client.getStats().matchStatus())
        .isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_DIFFERENT_CL.getNumber());
  }

  @Test
  public void parentDirectoryChange_invalidates() throws Exception {
    FileKey build = workspaceFile("pkg/sub/BUILD", 90);
    ByteString entry = putEntry(DATA_TYPE_FILE, fileInfo(build).cacheKey());

    // Deleting or renaming a parent shows up as a change to it.
    assertInvalidated(entry, ImmutableList.of(new DepotChange("pkg/sub", 101)), ImmutableList.of());
  }

  @Test
  public void listingDependency_invalidatedByAnAddedFile() throws Exception {
    FileKey dir = workspaceDirectory("pkg");
    var listingKey = DirectoryListingKey.create(dir.argument());
    ByteString entry = putEntry(DATA_TYPE_LISTING, listingInfo(listingKey).cacheKey());

    assertHit(entry, ImmutableList.of(), ImmutableList.of());
    assertInvalidated(
        entry, ImmutableList.of(new DepotChange("pkg/new.bzl", 101)), ImmutableList.of());
  }

  @Test
  public void nestedDependencies_sourceChangesDoNotInvalidateAnalysis() throws Exception {
    FileKey build = workspaceFile("pkg/BUILD", 50);
    FileKey defs = workspaceFile("pkg/defs.bzl", 60);
    FileKey source = workspaceFile("pkg/main.cc", 70);
    var nested =
        (AbstractNestedFileOpNodes)
            AbstractNestedFileOpNodes.from(ImmutableSet.<FileOpNode>of(build, defs), source);
    PackedFingerprint nodeKey = nodeInfo(nested).cacheKey();
    ByteString analysis = putEntry(DATA_TYPE_ANALYSIS_NODE, nodeKey);

    assertHit(analysis, ImmutableList.of(), ImmutableList.of("pkg/main.cc"));
    assertInvalidated(
        analysis, ImmutableList.of(new DepotChange("pkg/defs.bzl", 101)), ImmutableList.of());
    assertHit(analysis, ImmutableList.of(), ImmutableList.of());
  }

  @Test
  public void executionValue_isInvalidatedByAChangedSourceToo() throws Exception {
    FileKey build = workspaceFile("pkg/BUILD", 50);
    FileKey defs = workspaceFile("pkg/defs.bzl", 60);
    FileKey source = workspaceFile("pkg/main.cc", 70);
    var nested =
        (AbstractNestedFileOpNodes)
            AbstractNestedFileOpNodes.from(ImmutableSet.<FileOpNode>of(build, defs), source);
    ByteString execution = putEntry(DATA_TYPE_EXECUTION_NODE, nodeInfo(nested).cacheKey());

    assertHit(execution, ImmutableList.of(), ImmutableList.of());
    // An action reads the content of its sources, which the analysis of its target does not.
    assertInvalidated(execution, ImmutableList.of(), ImmutableList.of("pkg/main.cc"));
    assertInvalidated(
        execution, ImmutableList.of(new DepotChange("pkg/defs.bzl", 101)), ImmutableList.of());
  }

  @Test
  public void emptyDependencies_alwaysHit() throws Exception {
    ByteString entry = putEntry(DATA_TYPE_EMPTY, null);

    assertHit(entry, ImmutableList.of(new DepotChange("pkg/BUILD", 101)), ImmutableList.of("x"));
  }

  @Test
  public void saturatedStore_lookupIsAMissUntilItIsNot() throws Exception {
    ByteString entry = putEntry(DATA_TYPE_EMPTY, null);
    var client = client(ImmutableList.of(), ImmutableList.of());
    var saturated = new java.util.concurrent.atomic.AtomicBoolean(true);
    client.setSaturation(saturated::get);

    // Declined before anything is read: the target is analysed locally, and nothing that other
    // entries share is recorded as missing.
    var declined = client.lookup(entry.toByteArray()).get();
    assertThat(declined.value()).isEmpty();
    assertThat(declined.missReason())
        .isEqualTo(MissReason.MISS_REASON_CACHE_SATURATED.getNumber());

    saturated.set(false);
    assertThat(lookup(client, entry)).isEqualTo(ByteString.copyFrom(VALUE));
  }

  @Test
  public void absentEntry_isAMiss() throws Exception {
    var client = client(ImmutableList.of(), ImmutableList.of());

    assertThat(lookup(client, ByteString.copyFrom(fingerprint("never written").toBytes())))
        .isEmpty();
    assertThat(client.getValidationStats()).isEqualTo(new ValidationStats(0, 1, 0));
  }

  @Test
  public void missingDependencyData_isAMiss() throws Exception {
    // The entry names a dependency whose own record was never written.
    ByteString entry = putEntry(DATA_TYPE_FILE, "nonexistent key");

    // Any change at all, and the entry has to be checked against it, which it cannot be.
    assertInvalidated(entry, ImmutableList.of(), ImmutableList.of("unrelated/BUILD"));
    assertInvalidated(
        entry, ImmutableList.of(new DepotChange("unrelated/BUILD", 101)), ImmutableList.of());
  }

  @Test
  public void noChanges_dependencyDataIsNotRead() throws Exception {
    // A reader whose workspace is exactly what the writer uploaded from has nothing an entry's
    // dependencies could have changed to, so it does not fetch them, present or not.
    ByteString dangling = putEntry(DATA_TYPE_FILE, "nonexistent key");
    ByteString danglingNode =
        putEntry(DATA_TYPE_ANALYSIS_NODE, PackedFingerprint.getFingerprintForTesting("no node"));

    assertHit(dangling, ImmutableList.of(), ImmutableList.of());
    assertHit(danglingNode, ImmutableList.of(), ImmutableList.of());
    // A change the writer had already seen is not a change.
    assertHit(dangling, ImmutableList.of(new DepotChange("pkg/BUILD", 95)), ImmutableList.of());
  }

  @Test
  public void externalRepository_resolvesThroughTheSyntheticEntries() throws Exception {
    // Every record the serializer writes at the repository boundary has to be readable back, or
    // the reader sees missing data and nothing that touches a repository ever hits.
    workspaceFile("MODULE.bazel", 7);
    RootedPath link = externalPath("foo", "link");
    inject(FileKey.create(link), symlinkTo(externalPath("foo", "missing"), "missing"));
    RootedPath dir = externalPath("foo", "a");
    inject(FileKey.create(dir), NONEXISTENT_FILE_STATE_NODE);
    ByteString file = putEntry(DATA_TYPE_FILE, fileInfo(FileKey.create(link)).cacheKey());
    ByteString listing =
        putEntry(DATA_TYPE_LISTING, listingInfo(DirectoryListingKey.create(dir)).cacheKey());

    assertHit(file, ImmutableList.of(), ImmutableList.of());
    assertHit(listing, ImmutableList.of(), ImmutableList.of());
  }

  @Test
  public void workspaceBackedRepository_invalidatedByAnEditToTheWorkspaceFile() throws Exception {
    Path real = workspace.getRelative("tools/config/BUILD");
    FileSystemUtils.writeContentAsLatin1(createParents(real), "");
    versions.put(real, 42L);
    RootedPath realRootedPath = RootedPath.toRootedPath(Root.absoluteRoot(fs), real);
    RootedPath build = externalPath("local", "BUILD");
    inject(
        FileKey.create(build),
        new FileValue.SymlinkFileValueWithoutStoredChain(
            realRootedPath,
            FileStateValue.create(realRootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null),
            real.asFragment()));
    Path realDir = workspace.getRelative("tools/config");
    RootedPath realDirRootedPath = RootedPath.toRootedPath(Root.absoluteRoot(fs), realDir);
    RootedPath dir = externalPath("local", "");
    inject(
        FileKey.create(dir),
        new FileValue.SymlinkFileValueWithoutStoredChain(
            realDirRootedPath,
            FileStateValue.create(realDirRootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null),
            realDir.asFragment()));
    ByteString file = putEntry(DATA_TYPE_FILE, fileInfo(FileKey.create(build)).cacheKey());
    ByteString listing =
        putEntry(DATA_TYPE_LISTING, listingInfo(DirectoryListingKey.create(dir)).cacheKey());

    assertHit(file, ImmutableList.of(), ImmutableList.of());
    assertHit(listing, ImmutableList.of(), ImmutableList.of());
    assertInvalidated(file, ImmutableList.of(), ImmutableList.of("tools/config/BUILD"));
    assertInvalidated(listing, ImmutableList.of(), ImmutableList.of("tools/config/new.bzl"));
  }

  private void assertHit(
      ByteString entry,
      ImmutableList<DepotChange> depotChanges,
      ImmutableList<String> clientChanges)
      throws Exception {
    var client = client(depotChanges, clientChanges);
    assertThat(lookup(client, entry)).isEqualTo(ByteString.copyFrom(VALUE));
    assertThat(client.getValidationStats()).isEqualTo(new ValidationStats(1, 0, 0));
  }

  private void assertInvalidated(
      ByteString entry,
      ImmutableList<DepotChange> depotChanges,
      ImmutableList<String> clientChanges)
      throws Exception {
    var client = client(depotChanges, clientChanges);
    assertThat(lookup(client, entry)).isEmpty();
    assertThat(client.getValidationStats()).isEqualTo(new ValidationStats(0, 0, 1));
  }

  private ValidatingAnalysisCacheClient client(
      ImmutableList<DepotChange> depotChanges, ImmutableList<String> clientChanges) {
    return ValidatingAnalysisCacheClient.create(
        storage, WRITER_VERSION, depotChanges, clientChanges);
  }

  /** Returns the value a lookup answered, empty for a miss. */
  private static ByteString lookup(ValidatingAnalysisCacheClient client, ByteString key)
      throws Exception {
    return ByteString.copyFrom(client.lookup(key.toByteArray()).get().value());
  }

  /** Writes a top-level entry framed as {@code SelectedEntrySerializer} frames it. */
  private ByteString putEntry(DataType dataType, Object invalidationKey) throws Exception {
    var bytesOut = new ByteArrayOutputStream();
    CodedOutputStream codedOut = CodedOutputStream.newInstance(bytesOut);
    codedOut.writeEnumNoTag(dataType.getNumber());
    switch (invalidationKey) {
      case null -> {}
      case String key -> codedOut.writeStringNoTag(key);
      case PackedFingerprint key -> key.writeTo(codedOut);
      default -> throw new IllegalArgumentException(invalidationKey.toString());
    }
    codedOut.writeRawBytes(VALUE);
    codedOut.flush();
    PackedFingerprint key = fingerprint("entry " + nextEntry++);
    service.put(key, bytesOut.toByteArray()).get();
    return ByteString.copyFrom(key.toBytes());
  }

  private PackedFingerprint fingerprint(String name) {
    return service.fingerprint(name.getBytes(UTF_8));
  }

  private FileInvalidationDataInfo fileInfo(FileKey key) throws Exception {
    FileDataInfoOrFuture infoOrFuture = serializer.registerDependency(key);
    FileDataInfo info =
        switch (infoOrFuture) {
          case FileDataInfo done -> done;
          case FutureFileDataInfo future -> future.get();
        };
    var result = (FileInvalidationDataInfo) info;
    result.writeStatus().get();
    return result;
  }

  private ListingInvalidationDataInfo listingInfo(DirectoryListingKey key) throws Exception {
    ListingDataInfoOrFuture infoOrFuture = serializer.registerDependency(key);
    ListingDataInfo info =
        switch (infoOrFuture) {
          case ListingDataInfo done -> done;
          case FutureListingDataInfo future -> future.get();
        };
    var result = (ListingInvalidationDataInfo) info;
    result.writeStatus().get();
    return result;
  }

  private NodeInvalidationDataInfo nodeInfo(AbstractNestedFileOpNodes node) throws Exception {
    NodeDataInfoOrFuture infoOrFuture = serializer.registerDependency(node);
    NodeDataInfo info =
        switch (infoOrFuture) {
          case NodeDataInfo done -> done;
          case FutureNodeDataInfo future -> future.get();
        };
    var result = (NodeInvalidationDataInfo) info;
    result.writeStatus().get();
    return result;
  }

  /** Creates a workspace file and its parent directories, in the file system and the graph. */
  private FileKey workspaceFile(String relative, long version) throws Exception {
    Path path = workspace.getRelative(relative);
    FileSystemUtils.writeContentAsLatin1(createParents(path), "");
    PathFragment parent = PathFragment.create(relative).getParentDirectory();
    if (!parent.isEmpty()) {
      workspaceDirectory(parent.getPathString());
    }
    versions.put(path, version);
    return injectFileState(path);
  }

  private FileKey workspaceDirectory(String relative) throws Exception {
    Path path = workspace.getRelative(relative);
    path.createDirectoryAndParents();
    PathFragment parent = PathFragment.create(relative).getParentDirectory();
    if (!parent.isEmpty()) {
      workspaceDirectory(parent.getPathString());
    }
    return injectFileState(path);
  }

  private FileKey injectFileState(Path path) throws Exception {
    RootedPath rootedPath = RootedPath.toRootedPath(workspaceRoot, path);
    var key = FileKey.create(rootedPath);
    if (graph.getIfPresent(key) == null) {
      inject(key, FileStateValue.create(rootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null));
    }
    return key;
  }

  private RootedPath externalPath(String repository, String relative) {
    return RootedPath.toRootedPath(
        Root.fromPath(externalRoot.getRelative(repository)), PathFragment.create(relative));
  }

  private static FileValue symlinkTo(RootedPath realRootedPath, String target) {
    return new FileValue.SymlinkFileValueWithoutStoredChain(
        realRootedPath, NONEXISTENT_FILE_STATE_NODE, PathFragment.create(target));
  }

  private static Path createParents(Path path) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    return path;
  }

  private void inject(SkyKey key, SkyValue value) throws InterruptedException {
    NodeEntry entry =
        graph
            .createIfAbsentBatch(/* requestor= */ null, Reason.OTHER, ImmutableList.of(key))
            .get(key);
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();
    entry.setValue(value, Version.minimal(), /* maxTransitiveSourceVersion= */ null);
  }

  /** Versions from {@link #versions}, defaulting to {@link LongVersionGetter#MINIMAL}. */
  private final class TableVersionGetter implements LongVersionGetter {
    @Override
    public long getFilePathOrSymlinkVersion(Path path) {
      return versions.getOrDefault(path, LongVersionGetter.MINIMAL);
    }

    @Override
    public long getDirectoryListingVersion(Path path) {
      return versions.getOrDefault(path, LongVersionGetter.MINIMAL);
    }

    @Override
    public long getNonexistentPathVersion(Path path) {
      return versions.getOrDefault(path, LongVersionGetter.MINIMAL);
    }
  }
}
