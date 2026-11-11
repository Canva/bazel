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
package com.google.devtools.build.lib.skycache;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.ForkJoinPool.commonPool;
import static java.util.stream.Collectors.joining;

import build.aspect.skycache.v1.AnalysisCacheGrpc;
import build.aspect.skycache.v1.AnalysisCacheGrpc.AnalysisCacheFutureStub;
import build.aspect.skycache.v1.AnalysisCacheGrpc.AnalysisCacheStub;
import com.canva.skycache.v1.SnapshotIndexGrpc;
import com.canva.skycache.v1.SnapshotIndexGrpc.SnapshotIndexFutureStub;
import com.google.auth.Credentials;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutor;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutorOwner;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.Fingerprinter;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisMetadataWriter;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient;
import com.google.devtools.build.lib.util.SerializedAbruptExitException;
import com.google.devtools.common.options.OptionsProvider;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import javax.annotation.Nullable;

/**
 * Supplies both halves of the analysis cache from one gRPC connection.
 *
 * <p>Configured from {@link RemoteAnalysisCachingOptions}, {@link
 * RemoteAnalysisCachingServicesOptions} and the credentials {@link SkycacheModule} resolves;
 * nothing here reads the environment. With {@code --experimental_remote_analysis_cache} unset the
 * supplier stays inert and the module falls back to stock behaviour.
 */
final class SkycacheServicesSupplier implements RemoteAnalysisCachingServicesSupplier {

  /**
   * How long a partial write batch waits for company before being sent.
   *
   * <p>Not a flag, and not worth one: a write has nothing blocked behind it, so this only has to be
   * short enough to drain the tail of a build.
   */
  private static final long PUT_LINGER_MILLIS = 50;

  /**
   * Outbound message size ceiling, in bytes.
   *
   * <p>gRPC's 4 MiB default is below what a full write batch carries. The inbound direction needs
   * no setting here: {@link GoogleAuthUtils#newChannel} already lifts it for the same reason.
   */
  private static final int MAX_OUTBOUND_MESSAGE_BYTES = 256 * 1024 * 1024;

  /** Never shut down, so it outlives every command that borrows it; the common pool is shared. */
  private static final SafeExecutorOwner COMMAND_EXECUTOR = new SafeExecutorOwner(commonPool());

  /** Endpoint prefix selecting {@link Phase0InMemoryStore} instead of a gRPC service. */
  private static final String IN_MEMORY_SCHEME = "inmemory://";

  /**
   * What a reader checks entries against.
   *
   * @param validityHorizon the version every key is written at
   * @param clientChanges every path that differs from that version in this workspace
   * @param compressionService decompresses invalidation data, as the writer compressed it
   * @param fingerprinter keys invalidation data, as the writer keyed it
   */
  record Validation(
      int validityHorizon,
      ListenableFuture<ImmutableSet<String>> clientChanges,
      CompressionService compressionService,
      Fingerprinter fingerprinter) {}

  @Nullable private ManagedChannel channel;
  @Nullable private RemoteAnalysisCacheClient client;
  @Nullable private FingerprintValueStore store;
  @Nullable private GrpcFingerprintValueStore grpcStore;

  /** {@link #grpcStore}, or that and its mirrors. */
  @Nullable private FingerprintValueStore remoteStore;

  private ImmutableList<MirroredFingerprintValueStore.Mirror> mirrors = ImmutableList.of();
  private final List<ManagedChannel> mirrorChannels = new ArrayList<>();
  @Nullable private LocalAnalysisStore local;
  @Nullable private ReadQueue gets;
  @Nullable private StreamingFetchQueue streaming;
  @Nullable private String fetchSummaryAtReset;
  @Nullable private String referenceSummaryAtReset;
  @Nullable private ScheduledExecutorService scheduler;
  private long shedAtReset;

  /** Set by the module before any command that could configure this. */
  @Nullable private AuthAndTLSOptions authAndTlsOptions;

  @Nullable private Credentials credentials;

  /** Set by the module for each command that versions the workspace. */
  @Nullable private Validation validation;

  /** Set by the module for each command versioned at a mainline commit. */
  @Nullable private GitVersions.Base base;

  /** How the working tree differs from {@link #base}. */
  private ListenableFuture<ImmutableSet<String>> changes = immediateFuture(ImmutableSet.of());

  @Nullable private SnapshotIndexClient snapshotIndex;
  @Nullable private SkycacheMetadata metadata;

  @Nullable private ValidatingAnalysisCacheClient validatingClient;
  @Nullable private String validationSummaryAtShutdown;
  @Nullable private String localStoreFailure;

  /**
   * Hands over what the module resolved from Bazel's own credential machinery.
   *
   * <p>Separate from {@link #configure} because that signature belongs to the interface, and
   * because credential resolution needs the command environment, which it is not handed.
   */
  void setCredentials(AuthAndTLSOptions authAndTlsOptions, @Nullable Credentials credentials) {
    this.authAndTlsOptions = authAndTlsOptions;
    this.credentials = credentials;
  }

  /**
   * Has lookups checked against {@code validation}, or, if null, served as the store answers them.
   *
   * <p>Separate from {@link #configure} for the same reason as {@link #setCredentials}.
   */
  void setValidation(@Nullable Validation validation) {
    this.validation = validation;
  }

  /**
   * Has uploads recorded in, and downloads checked against, the service's snapshot index at {@code
   * base}, or, if null, neither.
   *
   * <p>Separate from {@link #configure} for the same reason as {@link #setCredentials}.
   *
   * @param changes how the working tree differs from {@code base}: an upload is recorded only if
   *     it does not
   */
  void setBase(
      @Nullable GitVersions.Base base, ListenableFuture<ImmutableSet<String>> changes) {
    this.base = base;
    this.changes = changes;
  }

  @Override
  public void configure(
      OptionsProvider optionsProvider,
      RemoteAnalysisCacheMode mode,
      @Nullable ClientId clientId,
      String buildId)
      throws SerializedAbruptExitException {
    RemoteAnalysisCachingOptions cachingOptions =
        optionsProvider.getOptions(RemoteAnalysisCachingOptions.class);
    String target = cachingOptions == null ? "" : nullToEmpty(cachingOptions.getRemoteAnalysisCache());
    SkycacheLocalOptions localOptions = optionsProvider.getOptions(SkycacheLocalOptions.class);
    String localPath = localOptions == null ? "" : localOptions.getLocalPath();
    if (target.isEmpty() && localPath.isEmpty()) {
      return;
    }
    if (channel != null || store != null) {
      return; // Already configured; flags are not allowed to change mid-command.
    }
    if (target.startsWith(IN_MEMORY_SCHEME)) {
      // No backend and no reader: an upload runs to completion so serialization can be measured.
      store = new Phase0InMemoryStore();
      installValidatingClient();
      return;
    }
    RemoteAnalysisCachingServicesOptions servicesOptions =
        optionsProvider.getOptions(RemoteAnalysisCachingServicesOptions.class);
    if (servicesOptions == null) {
      throw abruptExit(
          "the analysis cache was configured before its options were registered",
          RemoteAnalysisCaching.Code.REMOTE_ANALYSIS_CACHING_UNKNOWN);
    }

    SkycacheMirrorOptions mirrorOptions = optionsProvider.getOptions(SkycacheMirrorOptions.class);
    SkycacheFetchOptions fetchOptions = optionsProvider.getOptions(SkycacheFetchOptions.class);
    SnapshotIndex remoteIndex =
        target.isEmpty()
            ? null
            : connect(
                target,
                cachingOptions,
                servicesOptions,
                mirrorOptions == null ? ImmutableList.of() : mirrorOptions.getMirrors(),
                fetchOptions == null || fetchOptions.getFetchClosures());
    if (!localPath.isEmpty()) {
      try {
        local = LocalAnalysisStore.open(Path.of(localPath), localOptions.getLocalMaxSize());
      } catch (IOException e) {
        throw abruptExit(
            "opening the local analysis cache at " + localPath + ": " + e.getMessage(),
            RemoteAnalysisCaching.Code.REMOTE_ANALYSIS_CACHING_UNKNOWN);
      }
    }
    store =
        local == null
            ? remoteStore
            : remoteStore == null ? local : new TieredFingerprintValueStore(local, remoteStore);
    if (!mode.isUploadEnabled()) {
      // Around the tiers, not inside them: what a read brings from the service is still kept
      // locally, since the tiered store writes that to the local one itself.
      store = new ReadOnlyFingerprintValueStore(store);
    }

    if (base != null && servicesOptions.getAnalysisCacheEnableMetadataQueries()) {
      // Only a git-versioned command has a commit to record or look up, and only a store that
      // outlives the server keeps an index: an in-memory one's uploads are gone with it.
      SnapshotIndex index =
          local == null
              ? remoteIndex
              : remoteIndex == null ? local : SnapshotIndex.tiered(local, remoteIndex);
      FingerprintValueStore uploads = store;
      snapshotIndex =
          new SnapshotIndexClient(index, base, changes, uploads::getStats, Clock.systemUTC());
      metadata = new SkycacheMetadata();
    }
    installValidatingClient();
  }

  /**
   * Connects to the analysis cache service, setting up its store and its unvalidated client, and
   * returns its snapshot index.
   */
  private SnapshotIndex connect(
      String target,
      RemoteAnalysisCachingOptions cachingOptions,
      RemoteAnalysisCachingServicesOptions servicesOptions,
      List<String> mirrorTargets,
      boolean fetchClosures)
      throws SerializedAbruptExitException {
    if (authAndTlsOptions == null) {
      throw abruptExit(
          "the analysis cache was configured before its credentials were resolved",
          RemoteAnalysisCaching.Code.REMOTE_ANALYSIS_CACHING_UNKNOWN);
    }
    channel = newChannel(target);

    scheduler = Executors.newSingleThreadScheduledExecutor();
    // The deadline is applied per call, not here: withDeadlineAfter on a stored stub is absolute
    // from the moment the stub is made, so a build outliving it would see every later RPC fail.
    AnalysisCacheFutureStub stub =
        AnalysisCacheGrpc.newFutureStub(channel)
            .withMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_BYTES);
    SnapshotIndexFutureStub snapshotStub = SnapshotIndexGrpc.newFutureStub(channel);
    CallCredentials callCredentials =
        GoogleAuthUtils.newCallCredentialsProvider(credentials).getCallCredentials();
    if (callCredentials != null) {
      stub = stub.withCallCredentials(callCredentials);
      snapshotStub = snapshotStub.withCallCredentials(callCredentials);
    }
    long deadlineMillis = servicesOptions.getDeadline().toMillis();

    // One read queue for both readers. A response is the stored bytes whoever asked for them — the
    // cache client strips the invalidation header on its own side — so the two can share batches,
    // and sharing is what makes those batches full.
    BatchingGetQueue batches =
        new BatchingGetQueue(
            stub,
            servicesOptions.getReaderMaxBatchSize(),
            cachingOptions.getReadLinger().toMillis(),
            servicesOptions.getMaxInFlightReadRequests(),
            deadlineMillis,
            scheduler);
    if (fetchClosures) {
      AnalysisCacheStub streamStub = AnalysisCacheGrpc.newStub(channel);
      if (callCredentials != null) {
        streamStub = streamStub.withCallCredentials(callCredentials);
      }
      // No deadline: the stream lives as long as the command. Requests are written on the
      // scheduler's one thread, which the batches use only for their linger timers.
      streaming =
          new StreamingFetchQueue(
              streamStub, batches, servicesOptions.getReaderMaxBatchSize(), scheduler, commonPool());
      gets = streaming;
    } else {
      gets = batches;
    }

    grpcStore =
        new GrpcFingerprintValueStore(
            gets,
            new BatchingPutQueue(
                stub,
                servicesOptions.getMaxBatchSize(),
                PUT_LINGER_MILLIS,
                deadlineMillis,
                scheduler));
    // Strips headers without checking them; replaced when the command is git-versioned.
    client = new GrpcAnalysisCacheClient(gets);
    SnapshotIndex index = SnapshotIndex.of(snapshotStub);
    if (mirrorTargets.isEmpty()) {
      remoteStore = grpcStore;
      return index;
    }

    var mirrors = ImmutableList.<MirroredFingerprintValueStore.Mirror>builder();
    for (String mirrorTarget : mirrorTargets) {
      ManagedChannel mirrorChannel = newChannel(mirrorTarget);
      mirrorChannels.add(mirrorChannel);
      AnalysisCacheFutureStub mirrorStub =
          AnalysisCacheGrpc.newFutureStub(mirrorChannel)
              .withMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_BYTES);
      SnapshotIndexFutureStub mirrorSnapshotStub = SnapshotIndexGrpc.newFutureStub(mirrorChannel);
      if (callCredentials != null) {
        mirrorStub = mirrorStub.withCallCredentials(callCredentials);
        mirrorSnapshotStub = mirrorSnapshotStub.withCallCredentials(callCredentials);
      }
      mirrors.add(
          new MirroredFingerprintValueStore.Mirror(
              mirrorTarget,
              new BatchingPutQueue(
                  mirrorStub,
                  servicesOptions.getMaxBatchSize(),
                  PUT_LINGER_MILLIS,
                  deadlineMillis,
                  scheduler),
              SnapshotIndex.of(mirrorSnapshotStub)));
    }
    this.mirrors = mirrors.build();
    remoteStore = new MirroredFingerprintValueStore(grpcStore, this.mirrors);
    return SnapshotIndex.mirrored(index, this.mirrors);
  }

  /**
   * Opens a channel with Bazel's own builder rather than a hand-rolled one: it decides TLS from the
   * target's scheme the way --remote_cache does, applies --tls_certificate, the client certificate
   * and the grpc keepalive options, honours a proxy, and lifts the inbound message limit.
   */
  private ManagedChannel newChannel(String target) throws SerializedAbruptExitException {
    try {
      return GoogleAuthUtils.newChannel(
          /* executor= */ null,
          target,
          /* proxy= */ null,
          authAndTlsOptions,
          /* interceptors= */ null,
          /* serviceConfig= */ null);
    } catch (IOException e) {
      throw abruptExit(
          "connecting to the remote analysis cache at " + target + ": " + e.getMessage(),
          RemoteAnalysisCaching.Code.INVALID_SERVER_ADDRESS);
    }
  }

  /**
   * Replaces the client that strips headers without looking at them, if this command versions the
   * workspace. Reads the same store, so lookups share the batching queue with the value reads.
   */
  private void installValidatingClient() {
    if (validation == null) {
      return;
    }
    validatingClient =
        ValidatingAnalysisCacheClient.create(
            new ValidatingAnalysisCacheClient.Storage(
                store,
                COMMAND_EXECUTOR,
                validation.compressionService(),
                validation.fingerprinter()),
            validation.validityHorizon(),
            validation.clientChanges());
    validatingClient.setTopLevelTargetsLookup(snapshotIndex);
    ReadQueue reads = gets;
    if (reads != null) {
      validatingClient.setSaturation(
          () -> {
            if (!reads.saturated()) {
              return false;
            }
            reads.noteShed();
            return true;
          });
    }
    client = validatingClient;
  }

  private static SerializedAbruptExitException abruptExit(
      String message, RemoteAnalysisCaching.Code code) {
    return new SerializedAbruptExitException(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setRemoteAnalysisCaching(RemoteAnalysisCaching.newBuilder().setCode(code))
            .build()
            .toByteArray());
  }

  /**
   * Reads answered as misses because the in-flight cap was reached, or 0 when inert.
   *
   * <p>Survives {@link #resetCommandState}, so the module can report it after the reset that
   * releases the queue.
   */
  long shedReads() {
    return gets == null ? shedAtReset : gets.shed();
  }

  /**
   * Returns what the current or last command's lookups found, once, or null if none were checked.
   *
   * <p>Survives {@link #resetCommandState} for the same reason as {@link #shedReads}.
   */
  @Nullable
  String takeValidationSummary() {
    String summary;
    if (validatingClient == null) {
      summary = validationSummaryAtShutdown;
    } else {
      summary = summarize(validatingClient);
      // Taken before the reset, which would otherwise keep it for the next command.
      validatingClient = null;
    }
    validationSummaryAtShutdown = null;
    return summary;
  }

  /**
   * Returns why the last command's local writes could not be indexed, once, or null if they were.
   *
   * <p>Survives {@link #resetCommandState}, which is where they are indexed.
   */
  @Nullable
  String takeLocalStoreFailure() {
    String failure = localStoreFailure;
    localStoreFailure = null;
    return failure;
  }

  /**
   * Returns how the last command's reads were served, once, or null if none went over a stream.
   *
   * <p>Survives {@link #resetCommandState}, which is where the stream is ended.
   */
  @Nullable
  String takeFetchSummary() {
    String summary = streaming == null ? fetchSummaryAtReset : summarize(streaming);
    fetchSummaryAtReset = null;
    return summary;
  }

  /** Returns how many bytes of references the last command's upload carried, once, or null. */
  @Nullable
  String takeReferenceSummary() {
    String summary = referenceSummaryAtReset;
    referenceSummaryAtReset = null;
    return summary;
  }

  @Nullable
  private static String summarize(StreamingFetchQueue streaming) {
    long received = streaming.entriesReceived();
    if (received == 0 && streaming.keysRequested() == 0) {
      return null;
    }
    return String.format(
        "Analysis cache: received %d entries for %d keys asked for in %d requests; %d reads"
            + " were already delivered, %d were asked for twice; at most %d MB waited to be"
            + " read and %d MB never was",
        received,
        streaming.keysRequested(),
        streaming.batchesSent(),
        streaming.servedFromBuffer(),
        streaming.forced(),
        streaming.peakBufferedBytes() >> 20,
        streaming.unreadBytes() >> 20);
  }

  @Nullable
  private static String summarize(ValidatingAnalysisCacheClient client) {
    var stats = client.getValidationStats();
    long checked = stats.hits() + stats.absent() + stats.invalidated();
    if (checked == 0) {
      return null;
    }
    String reasons =
        client.getInvalidationReasons().entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(joining(", "));
    return String.format(
        "Analysis cache: checked %d entries: %d valid, %d not cached, %d invalidated%s",
        checked,
        stats.hits(),
        stats.absent(),
        stats.invalidated(),
        reasons.isEmpty() ? "" : " (" + reasons + ")")
        + (client.getFirstMissingData() == null
            ? ""
            : "; first failure: " + client.getFirstMissingData());
  }

  @Override
  @Nullable
  public ListenableFuture<FingerprintValueStore> getFingerprintValueStore() {
    return store == null ? null : immediateFuture(store);
  }

  @Override
  @Nullable
  public ListenableFuture<RemoteAnalysisCacheClient> getAnalysisCacheClient() {
    return client == null ? null : immediateFuture(client);
  }

  @Override
  @Nullable
  public ListenableFuture<RemoteAnalysisMetadataWriter> getMetadataWriter() {
    return snapshotIndex == null ? null : immediateFuture(snapshotIndex);
  }

  @Override
  @Nullable
  public SkycacheMetadataParams getSkycacheMetadataParams() {
    return metadata;
  }

  @Override
  public SafeExecutor getCommandExecutor() {
    return COMMAND_EXECUTOR;
  }

  @Override
  public void resetCommandState() {
    // Writes are acknowledged asynchronously, so anything still queued has to leave before the
    // channel does or the build reports a successful upload it never sent.
    if (grpcStore != null) {
      grpcStore.flush();
      long referenceBytes = grpcStore.referenceBytesSent();
      if (referenceBytes > 0) {
        referenceSummaryAtReset =
            String.format(
                "Analysis cache: sent %d bytes of references with the upload", referenceBytes);
      }
    }
    for (MirroredFingerprintValueStore.Mirror mirror : mirrors) {
      mirror.flush();
    }
    shedAtReset = gets == null ? 0 : gets.shed();
    if (streaming != null) {
      fetchSummaryAtReset = summarize(streaming);
      streaming = null;
    }
    if (gets != null) {
      // Before the scheduler stops, which is where the stream is ended.
      gets.close();
    }
    if (local != null) {
      // Indexes this command's writes, after which other servers can read them.
      try {
        local.close();
      } catch (IOException e) {
        localStoreFailure = "Analysis cache: this command's writes to the local store were lost: "
            + e.getMessage();
      }
      local = null;
    }
    if (validatingClient != null) {
      validationSummaryAtShutdown = summarize(validatingClient);
      validatingClient = null;
    }
    if (scheduler != null) {
      scheduler.shutdown();
      scheduler = null;
    }
    if (channel != null) {
      channel.shutdown();
      channel = null;
    }
    for (ManagedChannel mirrorChannel : mirrorChannels) {
      mirrorChannel.shutdown();
    }
    mirrorChannels.clear();
    mirrors = ImmutableList.of();
    remoteStore = null;
    client = null;
    validation = null;
    base = null;
    changes = immediateFuture(ImmutableSet.of());
    snapshotIndex = null;
    metadata = null;
    store = null;
    grpcStore = null;
    gets = null;
  }

  @Override
  public void blazeShutdown() {
    resetCommandState();
  }
}
