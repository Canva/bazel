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
import static com.google.common.util.concurrent.MoreExecutors.newDirectExecutorService;
import static com.google.devtools.build.lib.actions.FileStateValue.NONEXISTENT_FILE_STATE_NODE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.DIRECTORY_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.FILE_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.computeCacheKey;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantFileData.CONSTANT_FILE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantListingData.CONSTANT_LISTING;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.compress.CompressionServiceImpl;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutorOwner;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.skyframe.DirectoryListingKey;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.KeyValueWriter;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer.RepositoryBoundary;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileDataInfoOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FileInvalidationDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureFileDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.FutureListingDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingDataInfo;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingDataInfoOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ListingInvalidationDataInfo;
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
import java.io.IOException;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link FileDependencySerializer} at the external repository boundary. */
@RunWith(JUnit4.class)
public final class FileDependencySerializerRepositoryBoundaryTest {

  private final InMemoryFileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path workspace = fs.getPath("/ws");
  private final Path externalRoot = fs.getPath("/ob/external");
  private final RepositoryBoundary boundary = new RepositoryBoundary(workspace, externalRoot);

  private final InMemoryGraph graph = InMemoryGraph.create();
  private final RecordingWriter writer = new RecordingWriter();
  private final FakeVersionGetter versions = new FakeVersionGetter();

  @Test
  public void symlinkToMissingTarget_recordsRepositoryOnly() throws Exception {
    injectModuleFile("MODULE.bazel", 7);
    RootedPath link = externalPath("foo", "link");
    inject(FileKey.create(link), symlink(externalPath("foo", "missing"), "missing"));
    versions.rejectPathsUnder(externalRoot);

    var info =
        (FileInvalidationDataInfo)
            resolve(serializer(boundary).registerDependency(FileKey.create(link)));

    String expected = computeCacheKey("external/foo", 7, FILE_KEY_DELIMITER);
    assertThat(info.cacheKey()).isEqualTo(expected);
    // Plus the plain directory entry a reader resolves the parent through.
    assertThat(writer.keys).containsExactly(expected, directoryKey("external"));
  }

  @Test
  public void pathsOutsideWorkspaceAndRepositories_areConstant() throws Exception {
    // Where the repository contents cache puts what external/ symlinks to.
    RootedPath cached =
        RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/cache/abc/BUILD"));
    RootedPath cachedDir =
        RootedPath.toRootedPath(Root.absoluteRoot(fs), PathFragment.create("/cache/abc"));
    inject(FileKey.create(cached), NONEXISTENT_FILE_STATE_NODE);
    inject(FileKey.create(cachedDir), NONEXISTENT_FILE_STATE_NODE);
    versions.rejectPathsUnder(fs.getPath("/cache"));
    var serializer = serializer(boundary);

    assertThat(resolve(serializer.registerDependency(FileKey.create(cached))))
        .isEqualTo(CONSTANT_FILE);
    assertThat(resolve(serializer.registerDependency(DirectoryListingKey.create(cachedDir))))
        .isEqualTo(CONSTANT_LISTING);
    assertThat(writer.keys).isEmpty();
  }

  @Test
  public void boundary_comparesPathsAcrossFileSystemInstances() {
    var other = new InMemoryFileSystem(DigestHashFunction.SHA256);

    // The workspace and repositories as another file system instance names them.
    assertThat(boundary.isUnversioned(other.getPath("/ws/pkg/BUILD"))).isFalse();
    assertThat(boundary.workspaceRelative(other.getPath("/ws/pkg/BUILD")))
        .isEqualTo(PathFragment.create("pkg/BUILD"));
    assertThat(boundary.repositoryName(other.getPath("/ob/external/foo/BUILD"))).isEqualTo("foo");
    assertThat(boundary.isUnversioned(other.getPath("/cache/abc"))).isTrue();
  }

  @Test
  public void symlinkToMissingTarget_withoutBoundary_fails() throws Exception {
    // The failure this boundary exists to avoid: the target has no Skyframe node.
    RootedPath link = externalPath("foo", "link");
    inject(FileKey.create(link), symlink(externalPath("foo", "missing"), "missing"));

    var e =
        assertThrows(
            ExecutionException.class,
            () ->
                resolve(serializer(/* boundary= */ null).registerDependency(FileKey.create(link))));
    assertThat(e).hasCauseThat().isInstanceOf(MissingSkyframeEntryException.class);
  }

  @Test
  public void filesAndListingsInOneRepository_collapseToOneEntryEach() throws Exception {
    injectModuleFile("MODULE.bazel", 3);
    injectModuleFile("tools/deps.MODULE.bazel", 9);
    injectModuleFile("MODULE.bazel.lock", 5);
    RootedPath build = externalPath("foo", "a/BUILD");
    RootedPath defs = externalPath("foo", "a/defs.bzl");
    RootedPath dir = externalPath("foo", "a");
    inject(FileKey.create(build), NONEXISTENT_FILE_STATE_NODE);
    inject(FileKey.create(defs), NONEXISTENT_FILE_STATE_NODE);
    inject(FileKey.create(dir), NONEXISTENT_FILE_STATE_NODE);
    versions.rejectPathsUnder(externalRoot);
    var serializer = serializer(boundary);

    var buildInfo =
        (FileInvalidationDataInfo) resolve(serializer.registerDependency(FileKey.create(build)));
    var defsInfo =
        (FileInvalidationDataInfo) resolve(serializer.registerDependency(FileKey.create(defs)));
    var listingInfo =
        (ListingInvalidationDataInfo)
            resolve(serializer.registerDependency(DirectoryListingKey.create(dir)));

    // The newest module file decides the version.
    String fileKey = computeCacheKey("external/foo", 9, FILE_KEY_DELIMITER);
    String listingKey = computeCacheKey("external/foo", 9, DIRECTORY_KEY_DELIMITER);
    assertThat(buildInfo.cacheKey()).isEqualTo(fileKey);
    assertThat(defsInfo.cacheKey()).isEqualTo(fileKey);
    assertThat(listingInfo.cacheKey()).isEqualTo(listingKey);
    assertThat(writer.keys).containsExactly(fileKey, listingKey, directoryKey("external"));
  }

  @Test
  public void workspaceBackedRepository_recordsWorkspacePath() throws Exception {
    // local_repository and friends: the repository is a symlink into the workspace, so an edit to
    // the file must reach readers as a change to its workspace path.
    Path real = workspace.getRelative("tools/config/BUILD");
    FileSystemUtils.writeContentAsLatin1(createParents(real), "");
    RootedPath realRootedPath = RootedPath.toRootedPath(Root.absoluteRoot(fs), real);
    RootedPath build = externalPath("local", "BUILD");
    inject(
        FileKey.create(build),
        new FileValue.SymlinkFileValueWithoutStoredChain(
            realRootedPath,
            FileStateValue.create(realRootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null),
            real.asFragment()));
    versions.set(real, 42);

    var info =
        (FileInvalidationDataInfo)
            resolve(serializer(boundary).registerDependency(FileKey.create(build)));

    String expected = computeCacheKey("tools/config/BUILD", 42, FILE_KEY_DELIMITER);
    assertThat(info.cacheKey()).isEqualTo(expected);
    assertThat(writer.keys)
        .containsExactly(expected, directoryKey("tools/config"), directoryKey("tools"));
  }

  private static String directoryKey(String path) {
    return computeCacheKey(path, LongVersionGetter.MINIMAL, FILE_KEY_DELIMITER);
  }

  private FileDependencySerializer serializer(RepositoryBoundary boundary) {
    return new FileDependencySerializer(
        versions,
        graph,
        new CompressionServiceImpl(),
        writer,
        new SafeExecutorOwner(newDirectExecutorService()),
        /* profileCollector= */ null,
        boundary);
  }

  private RootedPath externalPath(String repository, String relative) {
    return RootedPath.toRootedPath(
        Root.fromPath(externalRoot.getRelative(repository)), PathFragment.create(relative));
  }

  private void injectModuleFile(String relative, long version) throws Exception {
    Path path = workspace.getRelative(relative);
    FileSystemUtils.writeContentAsLatin1(createParents(path), "");
    RootedPath rootedPath = RootedPath.toRootedPath(Root.fromPath(workspace), path);
    inject(
        FileKey.create(rootedPath),
        FileStateValue.create(rootedPath, SyscallCache.NO_CACHE, /* tsgm= */ null));
    versions.set(path, version);
  }

  private static Path createParents(Path path) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    return path;
  }

  private static FileValue symlink(RootedPath realRootedPath, String target) {
    return new FileValue.SymlinkFileValueWithoutStoredChain(
        realRootedPath, NONEXISTENT_FILE_STATE_NODE, PathFragment.create(target));
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

  private static FileDataInfo resolve(FileDataInfoOrFuture infoOrFuture) throws Exception {
    return switch (infoOrFuture) {
      case FileDataInfo info -> info;
      case FutureFileDataInfo future -> future.get();
    };
  }

  private static ListingDataInfo resolve(ListingDataInfoOrFuture infoOrFuture) throws Exception {
    return switch (infoOrFuture) {
      case ListingDataInfo info -> info;
      case FutureListingDataInfo future -> future.get();
    };
  }

  /** Records the keys written, which for these short paths are the cache keys verbatim. */
  private static final class RecordingWriter implements KeyValueWriter {
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @Override
    public WriteStatus put(KeyBytesProvider key, byte[] serializedBytes) {
      keys.add(new String(key.toBytes(), UTF_8));
      return WriteStatuses.immediateWriteStatus();
    }

    @Override
    public PackedFingerprint fingerprint(byte[] input) {
      return FingerprintValueService.NONPROD_FINGERPRINTER.fingerprint(input);
    }

    @Override
    public PackedFingerprint fingerprint(byte[] input, String salt) {
      return FingerprintValueService.NONPROD_FINGERPRINTER.fingerprint(input, salt);
    }
  }

  /**
   * Answers versions from a table, defaulting to {@link LongVersionGetter#MINIMAL}, and fails on
   * any path it was told the serializer must not version.
   */
  private static final class FakeVersionGetter implements LongVersionGetter {
    private final HashMap<Path, Long> versions = new HashMap<>();
    private Path rejected = null;

    void set(Path path, long version) {
      versions.put(path, version);
    }

    void rejectPathsUnder(Path root) {
      rejected = root;
    }

    @Override
    public long getFilePathOrSymlinkVersion(Path path) throws IOException {
      return lookup(path);
    }

    @Override
    public long getDirectoryListingVersion(Path path) throws IOException {
      return lookup(path);
    }

    @Override
    public long getNonexistentPathVersion(Path path) throws IOException {
      return lookup(path);
    }

    private long lookup(Path path) throws IOException {
      if (rejected != null && path.startsWith(rejected)) {
        throw new IOException("versioned a path under " + rejected + ": " + path);
      }
      return versions.getOrDefault(path, LongVersionGetter.MINIMAL);
    }
  }
}
