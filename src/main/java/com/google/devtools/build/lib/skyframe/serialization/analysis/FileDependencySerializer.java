// Copyright 2024 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateVoidFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static com.google.devtools.build.lib.actions.FileStateType.SYMLINK;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.aggregateWriteStatuses;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.DIRECTORY_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.FILE_KEY_DELIMITER;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.MAX_KEY_LENGTH;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.computeCacheKey;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantFileData.CONSTANT_FILE;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantListingData.CONSTANT_LISTING;
import static com.google.devtools.build.lib.skyframe.serialization.analysis.InvalidationDataInfoOrFuture.ConstantNodeData.CONSTANT_NODE;
import static com.google.devtools.build.lib.util.TestType.isInTest;
import static com.google.devtools.build.lib.vfs.RootedPath.toRootedPath;
import static java.lang.Math.max;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider.BundledFileSystem;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.concurrent.QuiescingFutureTask;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutor;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeFutures;
import com.google.devtools.build.lib.profiler.CounterSeriesCollector;
import com.google.devtools.build.lib.profiler.CounterSeriesTask;
import com.google.devtools.build.lib.profiler.CounterSeriesTask.Color;
import com.google.devtools.build.lib.profiler.CounterSeriesTaskImpl;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes.NestedFileOpNodes;
import com.google.devtools.build.lib.skyframe.AbstractNestedFileOpNodes.NestedFileOpNodesWithSource;
import com.google.devtools.build.lib.skyframe.DirectoryListingKey;
import com.google.devtools.build.lib.skyframe.FileKey;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode;
import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.RemoteFileOpNode;
import com.google.devtools.build.lib.skyframe.serialization.EntryPart;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.KeyValueWriter;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector;
import com.google.devtools.build.lib.skyframe.serialization.ProfileRecorder;
import com.google.devtools.build.lib.skyframe.serialization.StringKey;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatusBuilder;
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
import com.google.devtools.build.lib.skyframe.serialization.proto.DirectoryListingInvalidationData;
import com.google.devtools.build.lib.skyframe.serialization.proto.FileInvalidationData;
import com.google.devtools.build.lib.skyframe.serialization.proto.Symlink;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.InMemoryNodeEntry;
import com.google.errorprone.annotations.DoNotCall;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * Records {@link FileKey}, {@link DirectoryListingKey} or {@link AbstractNestedFileOpNodes}
 * invalidation to a remote {@link KeyValueWriter}.
 */
public final class FileDependencySerializer {

  /**
   * Treats an absolute symlink as an unresolvable leaf instead of crashing.
   *
   * <p>Analysis caching assumes an execroot free of absolute symlinks. An open-source output base
   * is not: every repository served from the repository contents cache is reached by one, as is
   * {@code external/rules_java++toolchains+local_jdk/lib}. Skipping them loses the dependency edge
   * on whatever they point at, so a change behind such a link will not invalidate a cached entry.
   *
   * <p>Set per command from the analysis cache options, so a stock build keeps the assertion.
   * Volatile because the writer is the command thread and the readers are the serializer's own
   * executor, which never synchronizes with it.
   */
  private static volatile boolean tolerateAbsoluteSymlinks = false;

  public static void setTolerateAbsoluteSymlinks(boolean tolerate) {
    tolerateAbsoluteSymlinks = tolerate;
  }

  /**
   * Counters for the progress of serialization.
   *
   * <p>Logged in the trace profile.
   */
  static class Counters implements CounterSeriesCollector {
    @VisibleForTesting final AtomicLong nodesWaitingForDeps = new AtomicLong();
    @VisibleForTesting final AtomicLong nodesWaitingForUpload = new AtomicLong();
    @VisibleForTesting final AtomicLong nodesUploaded = new AtomicLong();
    @VisibleForTesting final AtomicLong nodesWithProcessingErrors = new AtomicLong();
    @VisibleForTesting final AtomicLong keyBytesWaitingForUpload = new AtomicLong();
    @VisibleForTesting final AtomicLong valueBytesWaitingForUpload = new AtomicLong();
    @VisibleForTesting final AtomicLong keyBytesUploaded = new AtomicLong();
    @VisibleForTesting final AtomicLong valueBytesUploaded = new AtomicLong();

    private static final CounterSeriesTask NODES_WAITING_FOR_DEPS =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Nodes: Pending", "Waiting for deps", Color.RAIL_LOAD);
    private static final CounterSeriesTask NODES_WAITING_FOR_UPLOAD =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Nodes: Pending", "Waiting for upload", Color.RAIL_LOAD);
    private static final CounterSeriesTask NODES_UPLOADED =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Nodes: Uploaded", "Uploaded", Color.RAIL_RESPONSE);
    private static final CounterSeriesTask NODES_WITH_PROCESSING_ERRORS =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Nodes: Processing Errors",
            "Processing Errors",
            Color.RAIL_RESPONSE);

    private static final CounterSeriesTask KEY_BYTES_WAITING_FOR_UPLOAD =
        new CounterSeriesTaskImpl("Skycache: Invalidation: Bytes: Pending", "Key", Color.RAIL_LOAD);
    private static final CounterSeriesTask VALUE_BYTES_WAITING_FOR_UPLOAD =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Bytes: Pending", "Value", Color.RAIL_LOAD);
    private static final CounterSeriesTask KEY_BYTES_UPLOADED =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Bytes: Uploaded", "Key", Color.RAIL_RESPONSE);
    private static final CounterSeriesTask VALUE_BYTES_UPLOADED =
        new CounterSeriesTaskImpl(
            "Skycache: Invalidation: Bytes: Uploaded", "Value", Color.RAIL_RESPONSE);

    @Override
    public void collect(double deltaNanos, BiConsumer<CounterSeriesTask, Double> consumer) {
      consumer.accept(NODES_WAITING_FOR_DEPS, (double) nodesWaitingForDeps.get());
      consumer.accept(NODES_WAITING_FOR_UPLOAD, (double) nodesWaitingForUpload.get());
      consumer.accept(NODES_UPLOADED, (double) nodesUploaded.get());
      consumer.accept(NODES_WITH_PROCESSING_ERRORS, (double) nodesWithProcessingErrors.get());
      consumer.accept(KEY_BYTES_WAITING_FOR_UPLOAD, (double) keyBytesWaitingForUpload.get());
      consumer.accept(VALUE_BYTES_WAITING_FOR_UPLOAD, (double) valueBytesWaitingForUpload.get());
      consumer.accept(KEY_BYTES_UPLOADED, (double) keyBytesUploaded.get());
      consumer.accept(VALUE_BYTES_UPLOADED, (double) valueBytesUploaded.get());
    }
  }

  /**
   * Where external repositories live, and the workspace some of them are backed by.
   *
   * <p>Paths under {@link #externalRoot} cannot be versioned by the workspace's version control:
   * their contents are fetched, or served from the repository contents cache through an absolute
   * symlink, and Skyframe may hold no node for a path the serializer would otherwise follow there.
   * What decides a fetched repository's contents is already a dependency of anything that reads it,
   * through the module resolution and repository nodes, which depend on the module files, the
   * lockfile and whatever a repository rule read from the workspace.
   *
   * <p>So the serializer does not descend into a repository. A path inside one is recorded either
   * as the workspace path it resolves to, for repositories such as {@code local_repository} that
   * are symlinks into the workspace, or as a single {@code external/<name>} entry per repository
   * versioned at the module files, which a reader invalidates whenever any module file changed.
   *
   * <p>Paths outside both the workspace and {@link #externalRoot} are recorded as constant, like
   * the file system root. They are mostly the repository contents cache behind those absolute
   * symlinks, which is content addressed, and otherwise toolchain paths no version control sees,
   * which a reader therefore has to share with the writer.
   */
  public record RepositoryBoundary(Path workspaceRoot, Path externalRoot) {
    /**
     * Returns the repository {@code path} lies in, the empty string for {@link #externalRoot}
     * itself, or null if {@code path} is not under it.
     */
    @Nullable
    String repositoryName(Path path) {
      if (!isUnder(path, externalRoot)) {
        return null;
      }
      PathFragment relative = path.asFragment().relativeTo(externalRoot.asFragment());
      return relative.isEmpty() ? "" : relative.getSegment(0);
    }

    /** Whether {@code path} lies outside both the workspace and the external repositories. */
    boolean isUnversioned(Path path) {
      return !isUnder(path, workspaceRoot) && !isUnder(path, externalRoot);
    }

    /**
     * Whether {@code rootedPath} names a workspace path through a root other than the workspace,
     * such as the absolute root. Recorded under its own root it would be keyed by an absolute path
     * whose parent, outside the workspace, has no entry for a reader to resolve.
     */
    boolean isWorkspaceAlias(RootedPath rootedPath) {
      if (workspaceRelative(rootedPath.asPath()) == null) {
        return false;
      }
      Path root = rootedPath.getRoot().asPath(); // Null for the absolute root.
      return root == null || !root.asFragment().equals(workspaceRoot.asFragment());
    }

    /** Returns {@code path} relative to the workspace, or null if it lies outside it. */
    @Nullable
    PathFragment workspaceRelative(Path path) {
      if (!isUnder(path, workspaceRoot) || isUnder(path, externalRoot)) {
        return null;
      }
      return path.asFragment().relativeTo(workspaceRoot.asFragment());
    }

    /**
     * Compares by path alone. {@link Path#startsWith(Path)} also requires the same file system
     * instance, and the package roots and the command's directories need not share one, as when
     * the remote repository contents cache overlays the native file system.
     */
    private static boolean isUnder(Path path, Path root) {
      return path.asFragment().startsWith(root.asFragment());
    }
  }

  /**
   * The boundary applied to serializers created from now on, or null to follow every path.
   *
   * <p>Set per command alongside {@link #tolerateAbsoluteSymlinks}, so a stock build is unaffected.
   */
  @Nullable private static volatile RepositoryBoundary repositoryBoundary = null;

  public static void setRepositoryBoundary(@Nullable RepositoryBoundary boundary) {
    repositoryBoundary = boundary;
  }

  /** Whether {@code baseName} names a file that can change what an external repository holds. */
  private static boolean isModuleFile(String baseName) {
    return baseName.equals("MODULE.bazel")
        || baseName.endsWith(".MODULE.bazel")
        || baseName.equals("MODULE.bazel.lock")
        || baseName.equals("REPO.bazel");
  }

  @VisibleForTesting public static final int COMPRESSION_NUM_BYTES_THRESHOLD = 580;

  private final CompressionService compressionService;
  private final LongVersionGetter versionGetter;
  private final InMemoryGraph graph;
  private final KeyValueWriter writer;
  private final SafeExecutor executor;
  private final Counters counters;
  @Nullable private final ProfileCollector profileCollector;
  @Nullable private final RepositoryBoundary boundary;

  /** One synthetic file entry per external repository, keyed by repository name. */
  private final ConcurrentHashMap<String, FileInvalidationDataInfo> repositoryFileData =
      new ConcurrentHashMap<>();

  /** One synthetic listing entry per external repository, keyed by repository name. */
  private final ConcurrentHashMap<String, ListingInvalidationDataInfo> repositoryListingData =
      new ConcurrentHashMap<>();

  /** Write statuses of the directory entries {@link #writeDirectoryRecords} has written. */
  private final ConcurrentHashMap<PathFragment, WriteStatus> plainDirectoryRecords =
      new ConcurrentHashMap<>();

  /** The newest version of any module file in the graph, computed on first use. */
  private final Supplier<Long> moduleFilesVersion =
      Suppliers.memoize(this::computeModuleFilesVersion);

  private final ValueOrFutureMap<FileKey, FileDataInfoOrFuture, FileDataInfo, FutureFileDataInfo>
      fileDataInfo =
          new ValueOrFutureMap<>(
              new ConcurrentHashMap<>(),
              FutureFileDataInfo::new,
              this::populateFutureFileDataInfo,
              FutureFileDataInfo.class);

  private final ValueOrFutureMap<
          DirectoryListingKey, ListingDataInfoOrFuture, ListingDataInfo, FutureListingDataInfo>
      listingDataInfo =
          new ValueOrFutureMap<>(
              new ConcurrentHashMap<>(),
              FutureListingDataInfo::new,
              this::populateFutureListingDataInfo,
              FutureListingDataInfo.class);

  FileDependencySerializer(
      LongVersionGetter versionGetter,
      InMemoryGraph graph,
      CompressionService compressionService,
      KeyValueWriter writer,
      SafeExecutor executor,
      @Nullable ProfileCollector profileCollector) {
    this(
        versionGetter,
        graph,
        compressionService,
        writer,
        executor,
        profileCollector,
        repositoryBoundary);
  }

  @VisibleForTesting
  FileDependencySerializer(
      LongVersionGetter versionGetter,
      InMemoryGraph graph,
      CompressionService compressionService,
      KeyValueWriter writer,
      SafeExecutor executor,
      @Nullable ProfileCollector profileCollector,
      @Nullable RepositoryBoundary boundary) {
    this.versionGetter = versionGetter;
    this.graph = graph;
    this.writer = writer;
    this.compressionService = compressionService;
    this.executor = executor;
    this.counters = new Counters();
    this.profileCollector = profileCollector;
    this.boundary = boundary;
  }

  Counters getCounters() {
    return counters;
  }

  /**
   * Stores data about a {@code node} and its transitive dependencies in {@link #writer} to be used
   * for invalidation.
   *
   * <p>The resulting data can be embedded in reverse deps of {@code node} and used to invalidate
   * them by checking against a list of changed files and directory listings.
   *
   * <p>See comments at {@link FileInvalidationData} and {@link DirectoryListingInvalidationData}
   * for more details about the data being persisted.
   */
  InvalidationDataInfoOrFuture registerDependency(FileOpNode node) {
    return switch (node) {
      case FileKey file -> registerDependency(file);
      case DirectoryListingKey listing -> registerDependency(listing);
      case AbstractNestedFileOpNodes nested -> registerDependency(nested);
      case RemoteFileOpNode remote -> registerDependency(remote);
    };
  }

  NodeDataInfo registerDependency(RemoteFileOpNode node) {
    var reference = (NodeDataInfo) node.getSerializationScratch();
    if (reference != null) {
      return reference;
    }

    synchronized (node) {
      reference = (NodeDataInfo) node.getSerializationScratch();
      if (reference != null) {
        return reference;
      }

      var info =
          new NodeInvalidationDataInfo(
              PackedFingerprint.fromBytes(node.fingerprint().toByteArray()),
              WriteStatuses.immediateWriteStatus());
      node.setSerializationScratch(info);
      return info;
    }
  }

  FileDataInfoOrFuture registerDependency(FileKey key) {
    return fileDataInfo.getValueOrFuture(key);
  }

  ListingDataInfoOrFuture registerDependency(DirectoryListingKey key) {
    return listingDataInfo.getValueOrFuture(key);
  }

  /**
   * Registers a dependency on the set of transitive dependencies represented by {@code node}.
   *
   * <p>Uploads the result to the {@link #writer}.
   */
  NodeDataInfoOrFuture registerDependency(AbstractNestedFileOpNodes node) {
    var reference = (NodeDataInfoOrFuture) node.getSerializationScratch();
    if (reference != null) {
      return reference;
    }

    FutureNodeDataInfo future;
    synchronized (node) {
      reference = (NodeDataInfoOrFuture) node.getSerializationScratch();
      if (reference != null) {
        return reference;
      }

      future = new FutureNodeDataInfo(node);
      node.setSerializationScratch(future);
    }

    // If this is reached, this thread owns `future` and must set its value.
    try {
      return populateFutureNodeDataInfo(future);
    } finally {
      future.verifyComplete();
    }
  }

  /**
   * Populates the {@link FileDataInfoOrFuture} for the given {@link FutureFileDataInfo}.
   *
   * <p>This method is responsible for resolving the {@link FileKey} and its dependencies, and
   * uploading the resulting {@link FileInvalidationData} to the {@link #writer}.
   *
   * @param future The {@link FutureFileDataInfo} to populate.
   * @return The populated {@link FileDataInfoOrFuture}.
   */
  FileDataInfoOrFuture populateFutureFileDataInfo(FutureFileDataInfo future) {
    counters.nodesWaitingForDeps.incrementAndGet();

    FileKey key = future.key();
    RootedPath rootedPath = key.argument();
    if (boundary != null && !(rootedPath.getRoot().getFileSystem() instanceof BundledFileSystem)) {
      String repository = boundary.repositoryName(rootedPath.asPath());
      if (repository != null) {
        // Not tracked by the counters: nothing here waits on dependencies.
        counters.nodesWaitingForDeps.decrementAndGet();
        return populateRepositoryFileDataInfo(future, repository);
      }
      if (boundary.isUnversioned(rootedPath.asPath())) {
        counters.nodesWaitingForDeps.decrementAndGet();
        return future.completeWith(CONSTANT_FILE);
      }
      if (boundary.isWorkspaceAlias(rootedPath)) {
        counters.nodesWaitingForDeps.decrementAndGet();
        return populateRepositoryFileDataInfo(future, /* repository= */ null);
      }
    }
    RootedPath parentRootedPath;
    // Builtin files don't change.
    if ((rootedPath.getRoot().getFileSystem() instanceof BundledFileSystem)
        // Assumes that the root folder doesn't change.
        || (parentRootedPath = rootedPath.getParentDirectory()) == null) {
      counters.nodesWaitingForDeps.decrementAndGet();
      return future.completeWith(CONSTANT_FILE);
    }

    InMemoryNodeEntry nodeEntry = graph.getIfPresent(key);
    if (nodeEntry == null) {
      counters.nodesWaitingForDeps.decrementAndGet();
      counters.nodesWithProcessingErrors.incrementAndGet();
      return future.failWith(new MissingSkyframeEntryException(key));
    }
    var value = (FileValue) nodeEntry.getValue();
    RootedPath realRootedPath = value.realRootedPath(rootedPath);

    long initialMtsv;
    if (value.isDirectory()) {
      // Matches the behavior of PathVersionGetter.getVersionForExistingPathInternal.
      initialMtsv = LongVersionGetter.MINIMAL;
    } else {
      try {
        initialMtsv = getVersion(realRootedPath, value.exists());
      } catch (IOException e) {
        counters.nodesWaitingForDeps.decrementAndGet();
        counters.nodesWithProcessingErrors.incrementAndGet();
        return future.failWith(e);
      }
    }
    var uploader =
        new FileInvalidationDataUploader(
            /* rootedPath= */ rootedPath,
            /* parentRootedPath= */ parentRootedPath,
            /* realRootedPath= */ realRootedPath,
            value.exists(),
            initialMtsv);
    // The following steps are performed to ensure that ancestors and ancestor symlinks are resolved
    // to compute the correct MTSV:
    // 1. Call fullyResolvePath to register all the parents of the current rootedPath as
    //    dependencies first.
    // 2. The transform() method takes the output of the first parameter (a future) and passes it to
    //    the second parameter (a function).
    // 3. The output of fullyResolvePath is Void, so the transform method is only being used as a
    //    stop to not trigger the upload of the current rootedPath till its parents have been
    //    registered.
    // 4. The uploader itself is a Function that directly returns a FileDataInfo but gets wrapped as
    //    a future by the transform method.
    // 5. The upload happens through the put() operation in the writer inside the uploader.
    ListenableFuture<Void> resolutionFuture =
        fullyResolvePath(value.isSymlink() ? value.getUnresolvedLinkTarget() : null, uploader);

    Futures.addCallback(
        resolutionFuture,
        new FutureCallback<Void>() {
          @Override
          public void onSuccess(Void result) {
            // If resolution is successful, `uploader` completes the process via
            // `Futures.transform`. The counters will be handled in the `uploader` later.
          }

          @Override
          public void onFailure(Throwable t) {
            counters.nodesWaitingForDeps.decrementAndGet();
            counters.nodesWithProcessingErrors.incrementAndGet();
          }
        },
        directExecutor());

    return future.completeWith(Futures.transform(resolutionFuture, uploader, directExecutor()));
  }

  /**
   * Performs the upload of the {@link FileInvalidationData} once resolution is complete in the
   * {@link #apply} callback.
   */
  private final class FileInvalidationDataUploader
      implements Function<Void, FileInvalidationDataInfo> {
    private final RootedPath rootedPath;
    private final RootedPath parentRootedPath;
    private final RootedPath realRootedPath;
    private final boolean exists;

    private final FileInvalidationData.Builder data = FileInvalidationData.newBuilder();
    private final ArrayList<WriteStatus> writeStatuses = new ArrayList<>();
    private long mtsv;

    private FileInvalidationDataUploader(
        RootedPath rootedPath,
        RootedPath parentRootedPath,
        RootedPath realRootedPath,
        boolean exists,
        long initialMtsv) {
      this.rootedPath = rootedPath;
      this.parentRootedPath = parentRootedPath;
      this.realRootedPath = realRootedPath;
      this.exists = exists;
      this.mtsv = initialMtsv;
    }

    @Override
    public FileInvalidationDataInfo apply(Void unused) {
      String cacheKey = computeCacheKey(rootedPath.getRootRelativePath(), mtsv, FILE_KEY_DELIMITER);
      KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
      byte[] dataBytes = data.build().toByteArray();
      long keyByteCount = keyBytes.toBytes().length;
      long valueByteCount = dataBytes.length; // Don't hold on to the bytes any longer than needed

      counters.nodesWaitingForDeps.decrementAndGet();
      counters.nodesWaitingForUpload.incrementAndGet();
      counters.keyBytesWaitingForUpload.addAndGet(keyByteCount);
      counters.valueBytesWaitingForUpload.addAndGet(valueByteCount);

      WriteStatus writeStatus = writer.put(keyBytes, dataBytes);
      writeStatus.addListener(
          () -> {
            counters.nodesWaitingForUpload.decrementAndGet();
            counters.nodesUploaded.incrementAndGet();
            counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount);
            counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount);
            counters.keyBytesUploaded.addAndGet(keyByteCount);
            counters.valueBytesUploaded.addAndGet(valueByteCount);
          },
          directExecutor());
      if (profileCollector != null) {
        recordInvalidationProfile(
            profileCollector,
            InvalidationEntryType.FILE,
            keyByteCount,
            valueByteCount,
            writeStatus);
      }
      writeStatuses.add(writeStatus);
      return new FileInvalidationDataInfo(
          cacheKey, aggregateWriteStatuses(writeStatuses), exists, mtsv, realRootedPath);
    }

    /**
     * Adds information about the invalidation data of {@link #parentRootedPath}.
     *
     * <p>This is called at most once but might not be called at all if {@link #parentRootedPath}
     * refers to a constant path.
     */
    private void addParent(FileInvalidationDataInfo parent) {
      long parentMtsv = parent.mtsv();
      if (parentMtsv != LongVersionGetter.MINIMAL) {
        data.setParentMtsv(parentMtsv);
      }
      updateMtsvIfGreater(parentMtsv);
      writeStatuses.add(parent.writeStatus());
    }

    /**
     * Incorporates information about a symlink parent.
     *
     * <p>If a symlink is present, it's possible that combining the symlink with {@link
     * #parentRootedPath} points to a file who's parent directory hasn't been resolved. Resolution
     * of that parent directory results in {@code parentInfo}.
     *
     * <p>If the symlink points to a symlink, it's possible for that symlink to point to a file
     * having a yet another parent directory that has to be resolved again. In that case this method
     * would be called again.
     */
    private void addSymlinkParentInfo(FileInvalidationDataInfo parentInfo) {
      updateMtsvIfGreater(parentInfo.mtsv());
      writeStatuses.add(parentInfo.writeStatus());
    }

    private void updateMtsvIfGreater(long version) {
      if (version > mtsv) {
        mtsv = version;
      }
    }

    private Symlink.Builder addSymlinksBuilder() {
      return data.addSymlinksBuilder();
    }
  }

  /** Resolves the parent then resolves symlinks if {@code unresolvedLinkTarget} is non-null. */
  private ListenableFuture<Void> fullyResolvePath(
      @Nullable PathFragment unresolvedLinkTarget, FileInvalidationDataUploader uploader) {
    var pathResolver = new PathResolver(unresolvedLinkTarget, uploader);
    return switch (registerDependency(FileValue.key(uploader.parentRootedPath))) {
      case FileDataInfo parentData -> pathResolver.apply(parentData);
      case FutureFileDataInfo futureParentData ->
          Futures.transformAsync(futureParentData, pathResolver, directExecutor());
    };
  }

  /**
   * Waits for {@link FileInvalidationDataUploader#parentRootedPath} to be resolved (signalled
   * through the {@link #apply} callback) and starts symlink resolution if there is a symlink.
   */
  class PathResolver implements AsyncFunction<FileDataInfo, Void> {
    /** Symlink's target path. */
    @Nullable // non-null if there is a symlink
    private final PathFragment unresolvedLinkTarget;

    private final FileInvalidationDataUploader uploader;

    private PathResolver(
        @Nullable PathFragment unresolvedLinkTarget, FileInvalidationDataUploader uploader) {
      this.unresolvedLinkTarget = unresolvedLinkTarget;
      this.uploader = uploader;
    }

    @Override
    public ListenableFuture<Void> apply(FileDataInfo parentData) {
      RootedPath realParentPath;
      realParentPath =
          switch (parentData) {
            case CONSTANT_FILE ->
                // Assumes that BundledFileSystem does not symlink outside of BundledFileSystem.
                uploader.parentRootedPath;
            case FileInvalidationDataInfo parentReference -> {
              uploader.addParent(parentReference);
              // If the parent folder doesn't exist, unresolvedLinkTarget will be null.
              yield parentReference.realPath();
            }
          };

      if (unresolvedLinkTarget == null) {
        return immediateVoidFuture(); // No symlink processing needed.
      }

      Path linkPath; // Real path to the symlink.
      if (realParentPath.equals(uploader.parentRootedPath)) {
        linkPath = uploader.rootedPath.asPath();
      } else {
        linkPath =
            realParentPath
                .asPath()
                .getRelative(uploader.rootedPath.getRootRelativePath().getBaseName());
      }
      return processSymlinks(realParentPath, linkPath, unresolvedLinkTarget, uploader);
    }
  }

  /**
   * Recursively processes symlinks.
   *
   * <p>Requires that there are no symlink cycles (though ancestor references are benign). This is
   * assumed to hold for builds that succeed.
   *
   * @param parentRootedPath the real parent of the symlink
   * @param linkPath the real path to the symlink
   * @param link the target path contents of the symlink
   * @param uploader original uploader instance for the path resolution encountering this symlink
   */
  private ListenableFuture<Void> processSymlinks(
      RootedPath parentRootedPath,
      Path linkPath,
      PathFragment link,
      FileInvalidationDataUploader uploader) {
    if (link.isAbsolute()) {
      if (isInTest() || tolerateAbsoluteSymlinks) {
        // Test environments may use absolute symlinks, which aren't allowed in production
        // environments with analysis caching. Skips further dependency resolution for those.
        return immediateVoidFuture();
      }
      throw new IllegalStateException(
          String.format("Absolute symlink not permitted: %s contained %s", linkPath, link));
    }
    Symlink.Builder symlinkData = uploader.addSymlinksBuilder().setContents(link.getPathString());
    PathFragment linkParent = parentRootedPath.getRootRelativePath();
    PathFragment unresolvedTarget = linkParent.getRelative(link);

    // Assumes that there are no external symlinks, e.g. ones that go above root.
    checkArgument(
        !unresolvedTarget.containsUplevelReferences(),
        "symlink link above root for %s : %s = (%s) + (%s)",
        parentRootedPath,
        unresolvedTarget,
        linkParent,
        link);

    try {
      // Includes the version of the link itself in the MTSV.
      uploader.updateMtsvIfGreater(getVersion(linkPath, /* exists= */ true));
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }

    if (unresolvedTarget.isEmpty()) {
      // It was a symlink to root. It's unclear how this ever useful, but it's not illegal. No
      // resolution required.
      return immediateVoidFuture();
    }

    PathFragment unresolvedTargetParent = unresolvedTarget.getParentDirectory();

    if (linkParent.startsWith(unresolvedTargetParent)) {
      // Any ancestor directories of the fully resolved `linkParent` are already resolved so
      // there's no further ancestor resolution here.
      return processSymlinkTarget(
          toRootedPath(parentRootedPath.getRoot(), unresolvedTarget), uploader);
    }

    var parentProcessor = new SymlinkParentProcessor(parentRootedPath, link, uploader, symlinkData);

    // The parent path was changed by the link so it needs to be newly resolved.
    return switch (checkNotNull(
        registerDependency(
            FileValue.key(toRootedPath(parentRootedPath.getRoot(), unresolvedTargetParent))),
        unresolvedTargetParent)) {
      case FileDataInfo data -> parentProcessor.apply(data);
      case FutureFileDataInfo future ->
          Futures.transformAsync(future, parentProcessor, directExecutor());
    };
  }

  private ListenableFuture<Void> processSymlinkTarget(
      RootedPath resolvedSymlinkPath, FileInvalidationDataUploader uploader) {
    InMemoryNodeEntry nodeEntry = graph.getIfPresent(resolvedSymlinkPath);
    if (nodeEntry == null) {
      return immediateFailedFuture(new MissingSkyframeEntryException(resolvedSymlinkPath));
    }
    var symlinkValue = (FileStateValue) nodeEntry.getValue();
    if (!symlinkValue.getType().equals(SYMLINK)) {
      // We've come full circle back to the initial, fully resolved, FileValue. So there's no
      // additional bookkeeping needed.
      return immediateVoidFuture();
    }
    return processSymlinks(
        resolvedSymlinkPath.getParentDirectory(),
        resolvedSymlinkPath.asPath(),
        symlinkValue.getSymlinkTarget(),
        uploader);
  }

  /**
   * Waits for information about a symlink's real parent, signalled through the {@link #apply}
   * callback.
   *
   * <p>Once the real parent is known, syntactically combines it with the symlink target path. Then,
   * continues resolving the new path, potentially triggering recursion if the target is another
   * symlink.
   */
  private final class SymlinkParentProcessor implements AsyncFunction<FileDataInfo, Void> {
    private final RootedPath parentPath;
    private final PathFragment link;
    private final FileInvalidationDataUploader uploader;
    private final Symlink.Builder symlinkData;

    private SymlinkParentProcessor(
        RootedPath parentPath,
        PathFragment link,
        FileInvalidationDataUploader uploader,
        Symlink.Builder symlinkData) {
      this.parentPath = parentPath;
      this.link = link;
      this.uploader = uploader;
      this.symlinkData = symlinkData;
    }

    @Override
    public ListenableFuture<Void> apply(FileDataInfo realParentData) {
      RootedPath resolvedParentRootedPath;
      switch (realParentData) {
        case CONSTANT_FILE ->
            // Assumes that symlinks in BundledFileSystem do not escape.
            resolvedParentRootedPath = parentPath;
        case FileInvalidationDataInfo parentReference -> {
          uploader.addSymlinkParentInfo(parentReference);
          long parentMtsv = parentReference.mtsv();
          if (parentMtsv != LongVersionGetter.MINIMAL) {
            symlinkData.setParentMtsv(parentMtsv);
          }
          if (!parentReference.exists()) {
            // The parent folder doesn't exist so further resolution of the symlink is moot.
            return immediateVoidFuture();
          }
          resolvedParentRootedPath = parentReference.realPath();
        }
      }
      return processSymlinkTarget(
          toRootedPath(
              resolvedParentRootedPath.getRoot(),
              resolvedParentRootedPath.getRootRelativePath().getRelative(link.getBaseName())),
          uploader);
    }
  }

  private ListingDataInfoOrFuture populateFutureListingDataInfo(FutureListingDataInfo future) {
    counters.nodesWaitingForDeps.incrementAndGet();

    RootedPath rootedPath = future.key().argument();
    if (rootedPath.getRoot().getFileSystem() instanceof BundledFileSystem) {
      counters.nodesWaitingForDeps.decrementAndGet();
      return future.completeWith(CONSTANT_LISTING); // This listing doesn't change.
    }
    if (boundary != null) {
      String repository = boundary.repositoryName(rootedPath.asPath());
      if (repository != null) {
        // Not tracked by the counters: nothing here waits on dependencies.
        counters.nodesWaitingForDeps.decrementAndGet();
        return populateRepositoryListingDataInfo(future, repository);
      }
      if (boundary.isUnversioned(rootedPath.asPath())) {
        counters.nodesWaitingForDeps.decrementAndGet();
        return future.completeWith(CONSTANT_LISTING);
      }
      if (boundary.isWorkspaceAlias(rootedPath)) {
        counters.nodesWaitingForDeps.decrementAndGet();
        return populateRepositoryListingDataInfo(future, /* repository= */ null);
      }
    }
    var handler = new ListingFileHandler(rootedPath);
    switch (registerDependency(FileValue.key(rootedPath))) {
      case FileDataInfo info -> {
        return future.completeWith(handler.apply(info));
      }
      case FutureFileDataInfo futureInfo -> {
        return future.completeWith(Futures.transformAsync(futureInfo, handler, directExecutor()));
      }
    }
  }

  private class ListingFileHandler implements AsyncFunction<FileDataInfo, ListingDataInfo> {
    private final RootedPath rootedPath;

    private ListingFileHandler(RootedPath rootedPath) {
      this.rootedPath = rootedPath;
    }

    /**
     * Incorporates information from the file associated with the directory.
     *
     * <p>This code assumes that the directory exists, as does {@link
     * com.google.devtools.build.lib.skyframe.DirectoryListingValue#key}.
     */
    @Override
    public ListenableFuture<ListingDataInfo> apply(FileDataInfo info) {
      DirectoryListingInvalidationData.Builder data = DirectoryListingInvalidationData.newBuilder();
      var writeStatuses = new ArrayList<WriteStatus>();
      long fileMtsv;
      RootedPath realPath;
      switch (info) {
        case CONSTANT_FILE -> {
          // reached only for the root directory
          realPath = rootedPath;
          fileMtsv = LongVersionGetter.MINIMAL;
        }
        case FileInvalidationDataInfo fileInfo -> {
          writeStatuses.add(fileInfo.writeStatus());
          fileMtsv = fileInfo.mtsv();
          if (fileMtsv != LongVersionGetter.MINIMAL) {
            data.setFileMtsv(fileMtsv);
          }
          realPath = fileInfo.realPath();
        }
      }

      ListenableFuture<Long> dirMtsvFuture =
          SafeFutures.submit(
              (Callable<Long>)
                  () -> {
                    return versionGetter.getDirectoryListingVersion(realPath.asPath());
                  },
              executor);

      return Futures.transform(
          dirMtsvFuture,
          dirMtsv -> {
            long mtsv = max(dirMtsv, fileMtsv);

            String cacheKey =
                computeCacheKey(rootedPath.getRootRelativePath(), mtsv, DIRECTORY_KEY_DELIMITER);
            KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
            byte[] dataBytes = data.build().toByteArray();

            long keyByteCount = keyBytes.toBytes().length;
            long valueByteCount = dataBytes.length; // Do not hold on to the bytes

            counters.nodesWaitingForDeps.decrementAndGet();
            counters.nodesWaitingForUpload.incrementAndGet();
            counters.keyBytesWaitingForUpload.addAndGet(keyByteCount);
            counters.valueBytesWaitingForUpload.addAndGet(valueByteCount);

            WriteStatus writeStatus = writer.put(keyBytes, dataBytes);
            writeStatus.addListener(
                () -> {
                  counters.nodesWaitingForUpload.decrementAndGet();
                  counters.nodesUploaded.incrementAndGet();
                  counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount);
                  counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount);
                  counters.keyBytesUploaded.addAndGet(keyByteCount);
                  counters.valueBytesUploaded.addAndGet(valueByteCount);
                },
                directExecutor());
            if (profileCollector != null) {
              recordInvalidationProfile(
                  profileCollector,
                  InvalidationEntryType.LISTING,
                  keyByteCount,
                  valueByteCount,
                  writeStatus);
            }
            writeStatuses.add(writeStatus);
            return new ListingInvalidationDataInfo(cacheKey, aggregateWriteStatuses(writeStatuses));
          },
          directExecutor());
    }
  }

  NodeDataInfoOrFuture populateFutureNodeDataInfo(FutureNodeDataInfo future) {
    counters.nodesWaitingForDeps.incrementAndGet();

    var task = new NodeDependencyHandler(future.key());
    task.run();
    return future.completeWith(task);
  }

  OutputStream getCompressedOutputStream(OutputStream outputStream) throws IOException {
    // The default level and the fastest level (-7) results in 35% and 19% wall time overhead when
    // not using a threshold to compress, the default level provided a 2x better compression. Since
    // we do use a threshold and there is no wall time regression, we favor the better compression
    // ratio.
    return compressionService.newZstdOutputStream(outputStream);
  }

  /**
   * Accepts all the dependencies associated with a node, registers their serialization and waits
   * for processing to complete.
   *
   * <p>Once processing is complete and all keys are known, uploads the node value. {@link
   * #computeNodeBytes} defines the wire format of nodes.
   */
  private final class NodeDependencyHandler extends QuiescingFutureTask<NodeDataInfo>
      implements FutureCallback<Object> {
    private final AbstractNestedFileOpNodes node;

    private final ArrayList<String> fileKeys = new ArrayList<>();
    private final ArrayList<String> listingKeys = new ArrayList<>();
    private final ArrayList<NodeInvalidationDataInfo> nodeDependencies = new ArrayList<>();
    @Nullable private FileDataInfoOrFuture sourceFileOrFuture;

    private final WriteStatusBuilder writeStatusBuilder = new WriteStatusBuilder();

    private final ArrayList<FutureFileDataInfo> futureFileDataInfo = new ArrayList<>();
    private final ArrayList<FutureListingDataInfo> futureListingDataInfo = new ArrayList<>();
    private final ArrayList<FutureNodeDataInfo> futureNodeDataInfo = new ArrayList<>();

    private NodeDependencyHandler(AbstractNestedFileOpNodes node) {
      super(executor);
      this.node = node;
    }

    @Override
    protected void arrangeSubtasks() {
      // Loops through all node dependencies, registering them with this handler. This triggers
      // recursive registration, keeping track of immediate results and any futures.
      for (int i = 0; i < node.analysisDependenciesCount(); i++) {
        switch (node.getAnalysisDependency(i)) {
          case FileKey fileKey -> addFileKey(fileKey);
          case DirectoryListingKey listingKey -> addListingKey(listingKey);
          case AbstractNestedFileOpNodes nestedKeys -> addNodeKey(nestedKeys);
          case RemoteFileOpNode remoteNode -> addRemoteNode(remoteNode);
        }
      }

      switch (node) {
        case NestedFileOpNodes plainNodes -> {}
        case NestedFileOpNodesWithSource withSource -> setSourceFile(withSource.source());
      }
    }

    @Override
    protected NodeDataInfo getValue() {
      @Nullable String sourceFileKey;
      try {
        for (FutureFileDataInfo futureInfo : futureFileDataInfo) {
          addFileInfo(Futures.getDone(futureInfo));
        }
        for (FutureListingDataInfo futureInfo : futureListingDataInfo) {
          addListingInfo(Futures.getDone(futureInfo));
        }
        for (FutureNodeDataInfo futureInfo : futureNodeDataInfo) {
          addNodeInfo(Futures.getDone(futureInfo));
        }
        sourceFileKey = getSourceFileKey();
      } catch (ExecutionException e) {
        // The QuiescingFutureTask setup guarantees that ExecutionException will not be thrown if
        // getValue is called.
        throw new AssertionError("unexpected failure", e);
      }

      if (fileKeys.isEmpty() && listingKeys.isEmpty() && sourceFileKey == null) {
        // TODO(b/558805781): investigate whether this is always dead code in practice
        if (nodeDependencies.isEmpty()) {
          counters.nodesWaitingForDeps.decrementAndGet();
          return CONSTANT_NODE; // None of the dependencies are relevant to invalidation.
        }
        // There are multiple ways that result could become unary here, even if `node` always has at
        // least 2 children. The following may reduce child count.
        // 1. Deduplication.
        // 2. Constant references.
        // 3. NestedFileOpNodes with the same fingerprints.
        if (nodeDependencies.size() == 1) {
          // It ended up as a node wrapping another node. Discards the wrapper.
          //
          // TODO: b/364831651 - consider additional special casing for unary file or listing
          // dependencies.
          counters.nodesWaitingForDeps.decrementAndGet();
          return nodeDependencies.get(0);
        }
      }

      // We need to deduplicate and sort these entries so that serialization is deterministic
      // and compact.
      var sortedFileKeys = fileKeys.stream().sorted().distinct().toList();
      var sortedListingKeys = listingKeys.stream().sorted().distinct().toList();
      var sortedNodeDependencies =
          nodeDependencies.stream()
              .map(NodeInvalidationDataInfo::cacheKey)
              .sorted()
              .distinct()
              .toList();

      ProfileRecorder recorder =
          profileCollector == null ? null : new ProfileRecorder(profileCollector);
      if (recorder != null) {
        recorder.pushLocation(InvalidationEntryType.NODE);
        // We'll record the full entry size (including key) later once we have the key.
      }

      byte[] nodeBytes =
          computeNodeBytes(
              sortedNodeDependencies, sortedFileKeys, sortedListingKeys, sourceFileKey, recorder);
      byte[] maybeCompressedBytes = nodeBytes;
      if (maybeCompressedBytes.length >= COMPRESSION_NUM_BYTES_THRESHOLD) {
        maybeCompressedBytes = compressBytes(maybeCompressedBytes);
      }
      PackedFingerprint key = writer.fingerprint(maybeCompressedBytes);

      long keyByteCount = key.toBytes().length;
      long valueByteCount =
          maybeCompressedBytes.length; // Don't hold on to the bytes any longer than needed

      counters.nodesWaitingForDeps.decrementAndGet();
      counters.nodesWaitingForUpload.incrementAndGet();
      counters.keyBytesWaitingForUpload.addAndGet(keyByteCount);
      counters.valueBytesWaitingForUpload.addAndGet(valueByteCount);

      WriteStatus writeStatus = writer.put(key, maybeCompressedBytes);
      writeStatus.addListener(
          () -> {
            counters.nodesWaitingForUpload.decrementAndGet();
            counters.nodesUploaded.incrementAndGet();
            counters.keyBytesWaitingForUpload.addAndGet(-keyByteCount);
            counters.valueBytesWaitingForUpload.addAndGet(-valueByteCount);
            counters.keyBytesUploaded.addAndGet(keyByteCount);
            counters.valueBytesUploaded.addAndGet(valueByteCount);
          },
          directExecutor());

      if (recorder != null) {
        if (maybeCompressedBytes.length != nodeBytes.length) {
          recorder.setByteScale((double) maybeCompressedBytes.length / (double) nodeBytes.length);
        }
        recordKeyAndRegisterStatus(recorder, keyByteCount, valueByteCount, writeStatus);
      }

      writeStatusBuilder.add(writeStatus);
      return new NodeInvalidationDataInfo(key, writeStatusBuilder.build());
    }

    @Override
    protected void doneWithError(
        @Nullable Throwable primaryCause, ImmutableList<Throwable> secondaryCauses) {
      counters.nodesWaitingForDeps.decrementAndGet();
      counters.nodesWithProcessingErrors.incrementAndGet();
    }

    private void addRemoteNode(RemoteFileOpNode remoteNode) {
      addNodeInfo(registerDependency(remoteNode));
    }

    private void addFileKey(FileKey fileKey) {
      switch (registerDependency(fileKey)) {
        case FileDataInfo info -> addFileInfo(info);
        case FutureFileDataInfo futureInfo -> {
          futureFileDataInfo.add(futureInfo);
          trackFuture(futureInfo);
        }
      }
    }

    private void addFileInfo(FileDataInfo info) {
      switch (info) {
        case CONSTANT_FILE -> {}
        case FileInvalidationDataInfo fileInfo -> {
          fileKeys.add(fileInfo.cacheKey());
          writeStatusBuilder.add(fileInfo.writeStatus());
        }
      }
    }

    private void addListingKey(DirectoryListingKey listingKey) {
      switch (registerDependency(listingKey)) {
        case ListingDataInfo info -> addListingInfo(info);
        case FutureListingDataInfo futureInfo -> {
          futureListingDataInfo.add(futureInfo);
          trackFuture(futureInfo);
        }
      }
    }

    private void addListingInfo(ListingDataInfo info) {
      switch (info) {
        case CONSTANT_LISTING -> {}
        case ListingInvalidationDataInfo listingInfo -> {
          listingKeys.add(listingInfo.cacheKey());
          writeStatusBuilder.add(listingInfo.writeStatus());
        }
      }
    }

    private void addNodeKey(AbstractNestedFileOpNodes nestedKeys) {
      switch (registerDependency(nestedKeys)) {
        case NodeDataInfo info -> addNodeInfo(info);
        case FutureNodeDataInfo futureInfo -> {
          futureNodeDataInfo.add(futureInfo);
          trackFuture(futureInfo);
        }
      }
    }

    private void addNodeInfo(NodeDataInfo info) {
      switch (info) {
        case CONSTANT_NODE -> {}
        case NodeInvalidationDataInfo nodeInfo -> {
          nodeDependencies.add(nodeInfo);
          writeStatusBuilder.add(nodeInfo.writeStatus());
        }
      }
    }

    private void setSourceFile(FileKey sourceFile) {
      checkState(
          sourceFileOrFuture == null,
          "Attempting to set source file to %s, but it was already set to %s",
          sourceFile,
          sourceFileOrFuture);
      this.sourceFileOrFuture = registerDependency(sourceFile);
      switch (sourceFileOrFuture) {
        case FileDataInfo immediateSource -> {}
        case FutureFileDataInfo futureSource -> trackFuture(futureSource);
      }
    }

    private void trackFuture(ListenableFuture<?> future) {
      increment();
      Futures.addCallback(future, this, directExecutor());
    }

    @Override
    @DoNotCall("Only called via trackFuture")
    public void onSuccess(Object ignored) {
      decrement();
    }

    @Override
    @DoNotCall("Only called via trackFuture")
    public void onFailure(Throwable t) {
      notifyException(t);
    }

    private byte[] compressBytes(byte[] nodeBytes) {
      ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      try {
        MagicBytes.writeMagicBytes(outputStream);
        try (OutputStream compressedBytesStream =
            compressionService.newZstdOutputStream(outputStream)) {
          compressedBytesStream.write(nodeBytes);
        }
      } catch (IOException e) {
        throw new AssertionError("Unexpected IOException during in-memory compression", e);
      }
      return outputStream.toByteArray();
    }

    @Nullable
    private String getSourceFileKey() throws ExecutionException {
      if (sourceFileOrFuture == null) {
        return null;
      }
      return switch (switch (sourceFileOrFuture) {
        case FileDataInfo sourceInfo -> sourceInfo;
        case FutureFileDataInfo futureSourceInfo -> Futures.getDone(futureSourceInfo);
      }) {
        case CONSTANT_FILE -> null;
        case FileInvalidationDataInfo fileInfo -> {
          writeStatusBuilder.add(fileInfo.writeStatus());
          yield fileInfo.cacheKey();
        }
      };
    }
  }

  /**
   * Computes a canonical byte representation of the node.
   *
   * <p>Logically, a node is a set of string file or listing keys, as described at {@link
   * FileInvalidationData} and {@link DirectoryListingInvalidationData}, respectively, and a set of
   * {@link NestedFileOpNodes} fingerprints. Its byte representation is specified as follows.
   *
   * <ol>
   *   <li>The count of nested nodes, as a proto-encoded int.
   *   <li>The count of file keys, as a proto-encoded int.
   *   <li>The count of listing keys, as a proto-encoded int.
   *   <li>A proto-encoded boolean, true if a source file key is present and false otherwise.
   *   <li>Sorted and deduplicated, fingerprints of the {@link NestedFileOpNodes} byte
   *       representations.
   *   <li>Sorted and deduplicated, proto-encoded strings of the file keys.
   *   <li>Sorted and deduplicated, proto-encoded strings of the listing keys.
   *   <li>Proto-encoded string of the source file key, if applicable.
   * </ol>
   *
   * <p>More compact formats are possible, but this reduces the complexity of the deserializer.
   */
  @VisibleForTesting
  static byte[] computeNodeBytes(
      Collection<PackedFingerprint> nodeDependencyFingerprints,
      Collection<String> fileKeys,
      Collection<String> listingKeys,
      @Nullable String sourceFileKey,
      @Nullable ProfileRecorder recorder) {
    try {
      var bytesOut = new ByteArrayOutputStream();
      var codedOut = CodedOutputStream.newInstance(bytesOut);

      if (recorder != null) {
        recorder.pushLocation(EntryPart.VALUE);
      }
      int startValueBytes = codedOut.getTotalBytesWritten();

      codedOut.writeInt32NoTag(nodeDependencyFingerprints.size());
      codedOut.writeInt32NoTag(fileKeys.size());
      codedOut.writeInt32NoTag(listingKeys.size());
      codedOut.writeBoolNoTag(sourceFileKey != null);

      int startNodeDependencyBytes = codedOut.getTotalBytesWritten();
      for (PackedFingerprint fp : nodeDependencyFingerprints) {
        fp.writeTo(codedOut);
      }
      if (recorder != null) {
        recorder.pushLocation(NodeValuePart.NODE_DEPENDENCIES);
        recorder.recordBytesAndPopLocation(startNodeDependencyBytes, codedOut);
      }

      int startFileKeyBytes = codedOut.getTotalBytesWritten();
      for (String key : fileKeys) {
        codedOut.writeStringNoTag(key);
      }
      if (recorder != null) {
        recorder.pushLocation(NodeValuePart.FILE_KEYS);
        recorder.recordBytesAndPopLocation(startFileKeyBytes, codedOut);
      }

      int startListingKeyBytes = codedOut.getTotalBytesWritten();
      for (String key : listingKeys) {
        codedOut.writeStringNoTag(key);
      }
      if (recorder != null) {
        recorder.pushLocation(NodeValuePart.LISTING_KEYS);
        recorder.recordBytesAndPopLocation(startListingKeyBytes, codedOut);
      }

      if (sourceFileKey != null) {
        int startSourceFileBytes = codedOut.getTotalBytesWritten();
        codedOut.writeStringNoTag(sourceFileKey);
        if (recorder != null) {
          recorder.pushLocation(NodeValuePart.SOURCE_FILE);
          recorder.recordBytesAndPopLocation(startSourceFileBytes, codedOut);
        }
      }

      codedOut.flush();
      bytesOut.flush();
      if (recorder != null) {
        // Records bytes and pops the EntryPart.VALUE.
        recorder.recordBytesAndPopLocation(startValueBytes, codedOut);
      }

      return bytesOut.toByteArray();
    } catch (IOException e) {
      throw new AssertionError("Unexpected IOException from ByteArrayOutputStream", e);
    }
  }

  /**
   * Records a file inside an external repository without statting, versioning or following it.
   *
   * <p>See {@link RepositoryBoundary} for why nothing is lost by stopping here.
   *
   * @param repository the repository the path is in, or null for a workspace path reached through
   *     another root, which is recorded as the workspace path or, if it resolves outside the
   *     workspace, as constant
   */
  private FileDataInfoOrFuture populateRepositoryFileDataInfo(
      FutureFileDataInfo future, @Nullable String repository) {
    RootedPath rootedPath = future.key().argument();
    InMemoryNodeEntry nodeEntry = graph.getIfPresent(future.key());
    if (nodeEntry != null && nodeEntry.getValue() instanceof FileValue value) {
      RootedPath realRootedPath = value.realRootedPath(rootedPath);
      PathFragment workspacePath = boundary.workspaceRelative(realRootedPath.asPath());
      if (workspacePath != null) {
        // A repository backed by the workspace, such as local_repository: the file is a workspace
        // file, and an edit to it reaches readers as a change to its workspace path.
        var workspaceKey =
            FileKey.create(toRootedPath(Root.fromPath(boundary.workspaceRoot()), workspacePath));
        if (graph.getIfPresent(workspaceKey) != null) {
          // Recorded exactly as the main repository records it, so the two agree on the entry.
          return switch (registerDependency(workspaceKey)) {
            case FileDataInfo info -> future.completeWith(info);
            case FutureFileDataInfo futureInfo -> future.completeWith(futureInfo);
          };
        }
        long mtsv;
        try {
          mtsv =
              value.isDirectory()
                  ? LongVersionGetter.MINIMAL
                  : getVersion(realRootedPath, value.exists());
        } catch (IOException e) {
          return future.failWith(e);
        }
        var data = FileInvalidationData.newBuilder();
        String cacheKey = computeCacheKey(workspacePath, mtsv, FILE_KEY_DELIMITER);
        KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
        return future.completeWith(
            new FileInvalidationDataInfo(
                cacheKey,
                aggregateWriteStatuses(
                    ImmutableList.of(
                        writer.put(keyBytes, data.build().toByteArray()),
                        writeDirectoryRecords(workspacePath.getParentDirectory()))),
                value.exists(),
                mtsv,
                realRootedPath));
      }
    }
    try {
      return future.completeWith(
          repository == null ? CONSTANT_FILE : getRepositoryFileData(repository));
    } catch (IOException e) {
      return future.failWith(e);
    }
  }

  /** The listing counterpart of {@link #populateRepositoryFileDataInfo}. */
  private ListingDataInfoOrFuture populateRepositoryListingDataInfo(
      FutureListingDataInfo future, @Nullable String repository) {
    RootedPath rootedPath = future.key().argument();
    InMemoryNodeEntry nodeEntry = graph.getIfPresent(FileValue.key(rootedPath));
    if (nodeEntry != null && nodeEntry.getValue() instanceof FileValue value) {
      RootedPath realRootedPath = value.realRootedPath(rootedPath);
      PathFragment workspacePath = boundary.workspaceRelative(realRootedPath.asPath());
      if (workspacePath != null) {
        var workspaceKey =
            DirectoryListingKey.create(
                toRootedPath(Root.fromPath(boundary.workspaceRoot()), workspacePath));
        if (graph.getIfPresent(workspaceKey) != null) {
          return switch (registerDependency(workspaceKey)) {
            case ListingDataInfo info -> future.completeWith(info);
            case FutureListingDataInfo futureInfo -> future.completeWith(futureInfo);
          };
        }
        long mtsv;
        try {
          mtsv = versionGetter.getDirectoryListingVersion(realRootedPath.asPath());
        } catch (IOException e) {
          return future.failWith(e);
        }
        var data = DirectoryListingInvalidationData.newBuilder();
        String cacheKey = computeCacheKey(workspacePath, mtsv, DIRECTORY_KEY_DELIMITER);
        KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
        // A reader resolves a listing through the directory's own file entry, at MINIMAL here.
        return future.completeWith(
            new ListingInvalidationDataInfo(
                cacheKey,
                aggregateWriteStatuses(
                    ImmutableList.of(
                        writer.put(keyBytes, data.build().toByteArray()),
                        writeDirectoryRecords(workspacePath)))));
      }
    }
    try {
      return future.completeWith(
          repository == null ? CONSTANT_LISTING : getRepositoryListingData(repository));
    } catch (IOException e) {
      return future.failWith(e);
    }
  }

  /** Returns, writing it on first use, the single file entry standing for {@code repository}. */
  private FileInvalidationDataInfo getRepositoryFileData(String repository) throws IOException {
    long version = getModuleFilesVersion();
    return repositoryFileData.computeIfAbsent(
        repository,
        name -> {
          var data = FileInvalidationData.newBuilder();
          PathFragment keyPath = repositoryKeyPath(name);
          String cacheKey = computeCacheKey(keyPath, version, FILE_KEY_DELIMITER);
          KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
          return new FileInvalidationDataInfo(
              cacheKey,
              aggregateWriteStatuses(
                  ImmutableList.of(
                      writer.put(keyBytes, data.build().toByteArray()),
                      writeDirectoryRecords(keyPath.getParentDirectory()))),
              /* exists= */ true,
              version,
              toRootedPath(Root.fromPath(boundary.externalRoot()), PathFragment.create(name)));
        });
  }

  /** Returns, writing it on first use, the single listing entry standing for {@code repository}. */
  private ListingInvalidationDataInfo getRepositoryListingData(String repository)
      throws IOException {
    long version = getModuleFilesVersion();
    // A reader resolves the listing through the repository's file entry, so that has to exist.
    FileInvalidationDataInfo fileData = getRepositoryFileData(repository);
    return repositoryListingData.computeIfAbsent(
        repository,
        name -> {
          var data = DirectoryListingInvalidationData.newBuilder();
          if (version != LongVersionGetter.MINIMAL) {
            data.setFileMtsv(version);
          }
          String cacheKey =
              computeCacheKey(repositoryKeyPath(name), version, DIRECTORY_KEY_DELIMITER);
          KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
          return new ListingInvalidationDataInfo(
              cacheKey,
              aggregateWriteStatuses(
                  ImmutableList.of(
                      writer.put(keyBytes, data.build().toByteArray()), fileData.writeStatus())));
        });
  }

  /**
   * Writes the file entries for {@code directory} and its ancestors as plain directories, at
   * {@link LongVersionGetter#MINIMAL} with no symlinks, the way the main repository records an
   * unsymlinked directory.
   *
   * <p>Entries written here carry no parent version, and a reader reconstructs the parent's key
   * from that, so each ancestor has to be present or the reader sees missing data and treats the
   * entry as always invalid.
   */
  private WriteStatus writeDirectoryRecords(@Nullable PathFragment directory) {
    if (directory == null || directory.isEmpty()) {
      return immediateWriteStatus();
    }
    WriteStatus existing = plainDirectoryRecords.get(directory);
    if (existing != null) {
      return existing;
    }
    // Outside computeIfAbsent, which must not recurse into its own map.
    WriteStatus parent = writeDirectoryRecords(directory.getParentDirectory());
    return plainDirectoryRecords.computeIfAbsent(
        directory,
        dir -> {
          var data = FileInvalidationData.newBuilder();
          String cacheKey = computeCacheKey(dir, LongVersionGetter.MINIMAL, FILE_KEY_DELIMITER);
          KeyBytesProvider keyBytes = getKeyBytes(cacheKey, data::setOverflowKey);
          return aggregateWriteStatuses(
              ImmutableList.of(writer.put(keyBytes, data.build().toByteArray()), parent));
        });
  }

  /** The path a repository's synthetic entries are keyed by: {@code external/<name>}. */
  private static PathFragment repositoryKeyPath(String repository) {
    return LabelConstants.EXTERNAL_REPOSITORY_LOCATION.getRelative(repository);
  }

  private long getModuleFilesVersion() throws IOException {
    try {
      return moduleFilesVersion.get();
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  /**
   * Returns the newest version of any module file Skyframe read from the workspace.
   *
   * <p>Taken from the graph rather than a glob of the workspace: the files Bazel read are exactly
   * the ones that can have changed a repository, and walking a large workspace is not cheap.
   */
  private long computeModuleFilesVersion() {
    var moduleFiles = new ConcurrentLinkedQueue<FileValueAndPath>();
    graph.parallelForEach(
        entry -> {
          if (entry.getKey() instanceof FileKey fileKey
              && entry.getValue() instanceof FileValue value) {
            Path path = fileKey.argument().asPath();
            if (isModuleFile(path.getBaseName()) && boundary.workspaceRelative(path) != null) {
              moduleFiles.add(new FileValueAndPath(value, path));
            }
          }
        });
    long version = LongVersionGetter.MINIMAL;
    try {
      for (FileValueAndPath moduleFile : moduleFiles) {
        version = max(version, getVersion(moduleFile.path(), moduleFile.value().exists()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return version;
  }

  private record FileValueAndPath(FileValue value, Path path) {}

  private long getVersion(RootedPath rootedPath, boolean exists) throws IOException {
    return getVersion(rootedPath.asPath(), exists);
  }

  private long getVersion(Path path, boolean exists) throws IOException {
    return exists
        ? versionGetter.getFilePathOrSymlinkVersion(path)
        : versionGetter.getNonexistentPathVersion(path);
  }

  private KeyBytesProvider getKeyBytes(String cacheKey, Consumer<String> overflowConsumer) {
    if (cacheKey.length() > MAX_KEY_LENGTH) {
      overflowConsumer.accept(cacheKey);
      return writer.fingerprint(cacheKey.getBytes(UTF_8));
    }
    return new StringKey(cacheKey);
  }

  private static void recordInvalidationProfile(
      ProfileCollector profileCollector,
      InvalidationEntryType type,
      long keyByteCount,
      long valueByteCount,
      WriteStatus writeStatus) {
    var recorder = new ProfileRecorder(profileCollector);
    recorder.pushLocation(type);
    recorder.pushLocation(EntryPart.VALUE);
    recorder.recordBytes((int) valueByteCount);
    recorder.popLocation();
    recordKeyAndRegisterStatus(recorder, keyByteCount, valueByteCount, writeStatus);
  }

  private static void recordKeyAndRegisterStatus(
      ProfileRecorder recorder, long keyByteCount, long valueByteCount, WriteStatus writeStatus) {
    recorder.recordBytes((int) (keyByteCount + valueByteCount));
    recorder.pushLocation(EntryPart.KEY);
    recorder.recordBytes((int) keyByteCount);
    recorder.popLocation(); // Pop EntryPart.KEY.
    // EntryPart.VALUE is recorded already (e.g. by computeNodeBytes).
    recorder.popLocation(); // Pop InvalidationType (FILE/LISTING/NODE)
    recorder.registerWriteStatus(writeStatus);
  }
}
