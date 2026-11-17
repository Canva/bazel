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
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
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
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.References;
import com.google.devtools.build.lib.skyframe.serialization.ReferencingWriter;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
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
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient.AffectedEntries;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient.ClientChanges;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
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
  private final ReferenceRecordingStore store = new ReferenceRecordingStore();
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
  public void affected_entriesNamedAreMisses_andNoDependencyDataIsRead() throws Exception {
    // None of these has dependency data in the store: reading it would make each a miss.
    PackedFingerprint reached = PackedFingerprint.getFingerprintForTesting("reached");
    PackedFingerprint reachedByContents = PackedFingerprint.getFingerprintForTesting("by contents");
    PackedFingerprint notReached = PackedFingerprint.getFingerprintForTesting("not reached");
    ByteString file = putEntry(DATA_TYPE_FILE, "AQ:pkg/BUILD");
    ByteString otherFile = putEntry(DATA_TYPE_FILE, "AQ:other/BUILD");
    ByteString listing = putEntry(DATA_TYPE_LISTING, "AQ;pkg");
    ByteString analysis = putEntry(DATA_TYPE_ANALYSIS_NODE, reached);
    ByteString execution = putEntry(DATA_TYPE_EXECUTION_NODE, reached);
    ByteString analysisOverSource = putEntry(DATA_TYPE_ANALYSIS_NODE, reachedByContents);
    ByteString executionOverSource = putEntry(DATA_TYPE_EXECUTION_NODE, reachedByContents);
    ByteString untouched = putEntry(DATA_TYPE_EXECUTION_NODE, notReached);
    var asked = new AtomicInteger();
    var affected =
        new AffectedEntries(
            ImmutableSet.of(
                ByteString.copyFromUtf8("AQ:pkg/BUILD"),
                ByteString.copyFromUtf8("AQ;pkg"),
                ByteString.copyFrom(reached.toBytes())),
            ImmutableSet.of(ByteString.copyFrom(reachedByContents.toBytes())));
    var client =
        ValidatingAnalysisCacheClient.createWithListings(
            storage,
            WRITER_VERSION,
            immediateFuture(
                new ClientChanges(ImmutableSet.of("pkg/BUILD", "pkg/main.cc"), ImmutableSet.of())),
            changes -> {
              asked.incrementAndGet();
              return immediateFuture(affected);
            });

    assertThat(lookup(client, file)).isEmpty();
    assertThat(lookup(client, listing)).isEmpty();
    assertThat(lookup(client, analysis)).isEmpty();
    assertThat(lookup(client, execution)).isEmpty();
    // Only the contents of a source file below it changed, which analysis does not read.
    assertThat(lookup(client, analysisOverSource)).isEqualTo(ByteString.copyFrom(VALUE));
    assertThat(lookup(client, executionOverSource)).isEmpty();
    assertThat(lookup(client, otherFile)).isEqualTo(ByteString.copyFrom(VALUE));
    assertThat(lookup(client, untouched)).isEqualTo(ByteString.copyFrom(VALUE));
    assertThat(client.getValidationStats()).isEqualTo(new ValidationStats(3, 0, 5));
    assertThat(asked.get()).isEqualTo(1);
    assertThat(client.getAffectedSummary()).contains("3 dependency entries reached");
  }

  @Test
  public void affected_storeCannotSay_entriesAreCheckedAgainstTheirDependencies() throws Exception {
    FileKey build = workspaceFile("pkg/BUILD", 90);
    ByteString entry = putEntry(DATA_TYPE_FILE, fileInfo(build).cacheKey());
    ByteString dangling = putEntry(DATA_TYPE_FILE, "nonexistent key");
    var client =
        ValidatingAnalysisCacheClient.createWithListings(
            storage,
            WRITER_VERSION,
            immediateFuture(new ClientChanges(ImmutableSet.of("other/BUILD"), ImmutableSet.of())),
            changes -> immediateFailedFuture(new IOException("UNIMPLEMENTED")));

    assertThat(lookup(client, entry)).isEqualTo(ByteString.copyFrom(VALUE));
    // Its dependency data was read, and is not there.
    assertThat(lookup(client, dangling)).isEmpty();
    assertThat(client.getAffectedSummary()).contains("UNIMPLEMENTED");
  }

  @Test
  public void affected_notAskedWithoutChanges_orAboutAPathTooLongToBeAKey() throws Exception {
    ByteString dangling = putEntry(DATA_TYPE_FILE, "nonexistent key");
    var asked = new AtomicInteger();
    ValidatingAnalysisCacheClient.AffectedLookup lookup =
        changes -> {
          asked.incrementAndGet();
          return immediateFuture(new AffectedEntries(ImmutableSet.of(), ImmutableSet.of()));
        };

    var unchanged =
        ValidatingAnalysisCacheClient.createWithListings(
            storage,
            WRITER_VERSION,
            immediateFuture(new ClientChanges(ImmutableSet.of(), ImmutableSet.of())),
            lookup);
    assertThat(lookup(unchanged, dangling)).isEqualTo(ByteString.copyFrom(VALUE));
    assertThat(asked.get()).isEqualTo(0);

    // The entry of such a path is stored under a fingerprint, which a store cannot match to it.
    var longPath =
        ValidatingAnalysisCacheClient.createWithListings(
            storage,
            WRITER_VERSION,
            immediateFuture(
                new ClientChanges(ImmutableSet.of("d/" + "x".repeat(240)), ImmutableSet.of())),
            lookup);
    assertThat(lookup(longPath, dangling)).isEmpty();
    assertThat(asked.get()).isEqualTo(0);
  }

  /**
   * What the writer records has to be enough for a store to answer as a reader would: for every
   * change, each entry the reader finds invalid from its dependency data is one the store's walk
   * of the references reaches. Paths reached through symbolic links are where the two could part,
   * since an entry is keyed by the path as written and matched by the paths it resolves to.
   */
  @Test
  public void affected_storeWalkReachesWhatDependencyDataInvalidates_throughLinks()
      throws Exception {
    // real/a.txt, a directory link -> real, and a file pkg/alias.txt -> ../real/a.txt.
    FileKey realFile = workspaceFile("real/a.txt", 50);
    RootedPath realFilePath = realFile.argument();
    RootedPath realDir = RootedPath.toRootedPath(workspaceRoot, workspace.getRelative("real"));
    inject(realDir, FileStateValue.create(realDir, SyscallCache.NO_CACHE, /* tsgm= */ null));
    inject(
        realFilePath, FileStateValue.create(realFilePath, SyscallCache.NO_CACHE, /* tsgm= */ null));
    RootedPath link = RootedPath.toRootedPath(workspaceRoot, workspace.getRelative("link"));
    inject(
        FileKey.create(link),
        new FileValue.SymlinkFileValueWithoutStoredChain(
            realDir,
            FileStateValue.create(realDir, SyscallCache.NO_CACHE, /* tsgm= */ null),
            PathFragment.create("real")));
    FileKey throughLink =
        FileKey.create(
            RootedPath.toRootedPath(workspaceRoot, workspace.getRelative("link/a.txt")));
    inject(
        throughLink,
        new FileValue.DifferentRealPathFileValueWithoutStoredChain(
            realFilePath,
            FileStateValue.create(realFilePath, SyscallCache.NO_CACHE, /* tsgm= */ null)));
    FileKey build = workspaceFile("pkg/BUILD", 60);
    FileKey defs = workspaceFile("pkg/defs.bzl", 60);
    FileKey alias =
        FileKey.create(
            RootedPath.toRootedPath(workspaceRoot, workspace.getRelative("pkg/alias.txt")));
    inject(
        alias,
        new FileValue.SymlinkFileValueWithoutStoredChain(
            realFilePath,
            FileStateValue.create(realFilePath, SyscallCache.NO_CACHE, /* tsgm= */ null),
            PathFragment.create("../real/a.txt")));

    var sourceThroughLink =
        (AbstractNestedFileOpNodes)
            AbstractNestedFileOpNodes.from(ImmutableSet.<FileOpNode>of(build, defs), throughLink);
    var analysedThroughLink =
        (AbstractNestedFileOpNodes)
            AbstractNestedFileOpNodes.from(ImmutableSet.<FileOpNode>of(build, throughLink));
    ImmutableList<ByteString> entries =
        ImmutableList.of(
            putEntry(DATA_TYPE_FILE, fileInfo(throughLink).cacheKey()),
            putEntry(DATA_TYPE_LISTING, listingInfo(DirectoryListingKey.create(link)).cacheKey()),
            putEntry(DATA_TYPE_FILE, fileInfo(alias).cacheKey()),
            putEntry(DATA_TYPE_ANALYSIS_NODE, nodeInfo(sourceThroughLink).cacheKey()),
            putEntry(DATA_TYPE_EXECUTION_NODE, nodeInfo(sourceThroughLink).cacheKey()),
            putEntry(DATA_TYPE_ANALYSIS_NODE, nodeInfo(analysedThroughLink).cacheKey()),
            putEntry(DATA_TYPE_FILE, fileInfo(build).cacheKey()));
    // The markers of the paths behind the links.
    assertThat(store.keys())
        .containsAtLeast(
            ByteString.copyFromUtf8("~:real/a.txt"),
            ByteString.copyFromUtf8("~:real"),
            ByteString.copyFromUtf8("~;real"));

    ImmutableList<ClientChanges> changes =
        ImmutableList.of(
            new ClientChanges(ImmutableSet.of("real/a.txt"), ImmutableSet.of()),
            new ClientChanges(ImmutableSet.of("real"), ImmutableSet.of()),
            new ClientChanges(ImmutableSet.of("real/new.txt"), ImmutableSet.of("real")),
            new ClientChanges(ImmutableSet.of("link"), ImmutableSet.of("")),
            new ClientChanges(ImmutableSet.of("link/a.txt"), ImmutableSet.of("link")),
            new ClientChanges(ImmutableSet.of("pkg/alias.txt"), ImmutableSet.of()),
            new ClientChanges(ImmutableSet.of("pkg/BUILD"), ImmutableSet.of()),
            new ClientChanges(ImmutableSet.of("pkg"), ImmutableSet.of()),
            new ClientChanges(ImmutableSet.of("other/x.txt"), ImmutableSet.of("other")));
    var invalidByDependencyData = new ArrayList<List<Boolean>>();
    for (ClientChanges change : changes) {
      var fromDependencyData = new ArrayList<Boolean>();
      var fromStoreWalk = new ArrayList<Boolean>();
      for (ByteString entry : entries) {
        fromDependencyData.add(
            lookup(
                    ValidatingAnalysisCacheClient.createWithListings(
                        storage, WRITER_VERSION, immediateFuture(change)),
                    entry)
                .isEmpty());
        fromStoreWalk.add(
            lookup(
                    ValidatingAnalysisCacheClient.createWithListings(
                        storage,
                        WRITER_VERSION,
                        immediateFuture(change),
                        asked -> immediateFuture(store.walkFrom(asked))),
                    entry)
                .isEmpty());
      }
      for (int i = 0; i < entries.size(); i++) {
        if (fromDependencyData.get(i)) {
          assertWithMessage("entry %s after %s", i, change).that(fromStoreWalk.get(i)).isTrue();
        }
      }
      invalidByDependencyData.add(fromDependencyData);
      // The walk may reach more, by the path of a key that a reader would not match. For the
      // paths behind the links it reaches exactly as much.
      if (change.paths().contains("real/a.txt")
          || change.paths().contains("real/new.txt")
          || change.paths().contains("pkg/BUILD")
          || change.paths().contains("other/x.txt")) {
        assertWithMessage("after %s", change).that(fromStoreWalk).isEqualTo(fromDependencyData);
      }
    }
    // The file behind the link: everything that reads it through either link, and not the
    // analysis value that has it only as a source, nor the listing, nor the BUILD file.
    assertThat(invalidByDependencyData.get(0))
        .containsExactly(true, false, true, false, true, true, false)
        .inOrder();
    // A file added behind the link changes the listing read through it and nothing else.
    assertThat(invalidByDependencyData.get(2))
        .containsExactly(false, true, false, false, false, false, false)
        .inOrder();
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

  /**
   * Keeps the references entries are written with, and walks them as the analysis cache service
   * does to name the entries a change reaches.
   */
  private static final class ReferenceRecordingStore extends InMemoryFingerprintValueStore
      implements ReferencingWriter {
    private final ConcurrentHashMap<ByteString, byte[]> references = new ConcurrentHashMap<>();

    @Override
    public WriteStatus put(
        KeyBytesProvider key, byte[] serializedBytes, @Nullable byte[] references) {
      if (references != null) {
        this.references.put(ByteString.copyFrom(key.toBytes()), references);
      }
      return put(key, serializedBytes);
    }

    private record Referrer(ByteString key, boolean contents) {}

    /**
     * From the entries and markers of the changed paths to everything that references them,
     * noting what is reached only across a reference after a separator.
     */
    AffectedEntries walkFrom(ClientChanges changes) {
      var referrers = new HashMap<Long, List<Referrer>>();
      references.forEach(
          (key, packed) -> {
            // Only through dependency entries: those of files and listings, and the sets of
            // dependencies, which all have a separator.
            if (dependencyPath(key) == null && !hasSeparator(packed)) {
              return;
            }
            ByteBuffer buffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
            boolean contents = false;
            while (buffer.remaining() >= References.BYTES) {
              long hash = buffer.getLong();
              if (hash == References.CONTENTS_SEPARATOR) {
                contents = true;
              } else {
                referrers
                    .computeIfAbsent(hash, unused -> new ArrayList<>())
                    .add(new Referrer(key, contents));
              }
            }
          });
      var reached = new HashSet<ByteString>();
      var inFull = new HashSet<ByteString>();
      var frontier = new ArrayDeque<Referrer>();
      for (ByteString key : keys()) {
        String path = dependencyPath(key);
        if (path != null
            && (path.charAt(0) == ':'
                ? changes.paths().contains(path.substring(1))
                : changes.listings().contains(path.substring(1)))) {
          reached.add(key);
          inFull.add(key);
          frontier.add(new Referrer(key, /* contents= */ false));
        }
      }
      while (!frontier.isEmpty()) {
        Referrer at = frontier.remove();
        byte[] key = at.key().toByteArray();
        for (Referrer referrer :
            referrers.getOrDefault(References.hash(key, 0, key.length), ImmutableList.of())) {
          boolean contents = at.contents() || referrer.contents();
          if (contents ? reached.add(referrer.key()) : inFull.add(referrer.key())) {
            reached.add(referrer.key());
            frontier.add(new Referrer(referrer.key(), contents));
          }
        }
      }
      var analysis = ImmutableSet.<ByteString>builder();
      var contentsOnly = ImmutableSet.<ByteString>builder();
      for (ByteString key : reached) {
        if (key.byteAt(0) == '~') {
          continue; // A marker.
        }
        (inFull.contains(key) ? analysis : contentsOnly).add(key);
      }
      return new AffectedEntries(analysis.build(), contentsOnly.build());
    }

    private static boolean hasSeparator(byte[] packed) {
      ByteBuffer buffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
      while (buffer.remaining() >= References.BYTES) {
        if (buffer.getLong() == References.CONTENTS_SEPARATOR) {
          return true;
        }
      }
      return false;
    }

    /** The key from its delimiter on, if it is that of a file, a listing or a marker. */
    @Nullable
    private static String dependencyPath(ByteString key) {
      String text = key.toStringUtf8();
      if (text.startsWith("~:") || text.startsWith("~;")) {
        return text.substring(1);
      }
      for (int i = 0; i < text.length() && i <= 12; i++) {
        char c = text.charAt(i);
        if (c == ':' || c == ';') {
          return text.substring(i);
        }
        if (!(Character.isLetterOrDigit(c) && c < 128) && c != '+' && c != '/') {
          return null;
        }
      }
      return null;
    }
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
