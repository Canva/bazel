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

import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.ForkJoinPool.commonPool;

import build.aspect.skycache.v1.AnalysisCacheGrpc;
import build.aspect.skycache.v1.AnalysisCacheGrpc.AnalysisCacheFutureStub;
import com.google.auth.Credentials;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutor;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutorOwner;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.util.SerializedAbruptExitException;
import com.google.devtools.common.options.OptionsProvider;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import java.io.IOException;
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

  @Nullable private ManagedChannel channel;
  @Nullable private GrpcAnalysisCacheClient client;
  @Nullable private FingerprintValueStore store;
  @Nullable private GrpcFingerprintValueStore grpcStore;
  @Nullable private BatchingGetQueue gets;
  @Nullable private ScheduledExecutorService scheduler;
  private long shedAtReset;

  /** Set by the module before any command that could configure this. */
  @Nullable private AuthAndTLSOptions authAndTlsOptions;

  @Nullable private Credentials credentials;

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

  @Override
  public void configure(
      OptionsProvider optionsProvider,
      RemoteAnalysisCacheMode mode,
      @Nullable ClientId clientId,
      String buildId)
      throws SerializedAbruptExitException {
    RemoteAnalysisCachingOptions cachingOptions =
        optionsProvider.getOptions(RemoteAnalysisCachingOptions.class);
    String target = cachingOptions == null ? null : cachingOptions.getRemoteAnalysisCache();
    if (target == null || target.isEmpty()) {
      return;
    }
    if (channel != null || store != null) {
      return; // Already configured; flags are not allowed to change mid-command.
    }
    if (target.startsWith(IN_MEMORY_SCHEME)) {
      // No backend and no reader: an upload runs to completion so serialization can be measured.
      store = new Phase0InMemoryStore();
      return;
    }
    RemoteAnalysisCachingServicesOptions servicesOptions =
        optionsProvider.getOptions(RemoteAnalysisCachingServicesOptions.class);
    if (authAndTlsOptions == null || servicesOptions == null) {
      throw abruptExit(
          "the analysis cache was configured before its options and credentials were resolved",
          RemoteAnalysisCaching.Code.REMOTE_ANALYSIS_CACHING_UNKNOWN);
    }

    try {
      // Bazel's own channel builder rather than a hand-rolled one: it decides TLS from the target's
      // scheme the way --remote_cache does, applies --tls_certificate and the grpc keepalive
      // options, honours a proxy, and lifts the inbound message limit.
      channel =
          GoogleAuthUtils.newChannel(
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

    scheduler = Executors.newSingleThreadScheduledExecutor();
    // The deadline is applied per call, not here: withDeadlineAfter on a stored stub is absolute
    // from the moment the stub is made, so a build outliving it would see every later RPC fail.
    AnalysisCacheFutureStub stub =
        AnalysisCacheGrpc.newFutureStub(channel)
            .withMaxOutboundMessageSize(MAX_OUTBOUND_MESSAGE_BYTES);
    CallCredentials callCredentials =
        GoogleAuthUtils.newCallCredentialsProvider(credentials).getCallCredentials();
    if (callCredentials != null) {
      stub = stub.withCallCredentials(callCredentials);
    }
    long deadlineMillis = servicesOptions.getDeadline().toMillis();

    // One read queue for both readers. A response is the stored bytes whoever asked for them — the
    // cache client strips the invalidation header on its own side — so the two can share batches,
    // and sharing is what makes those batches full.
    gets =
        new BatchingGetQueue(
            stub,
            servicesOptions.getReaderMaxBatchSize(),
            cachingOptions.getReadLinger().toMillis(),
            servicesOptions.getMaxInFlightReadRequests(),
            deadlineMillis,
            scheduler);

    grpcStore =
        new GrpcFingerprintValueStore(
            gets,
            new BatchingPutQueue(
                stub,
                servicesOptions.getMaxBatchSize(),
                PUT_LINGER_MILLIS,
                deadlineMillis,
                scheduler));
    store = grpcStore;
    client = new GrpcAnalysisCacheClient(gets);
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

  @Override
  @Nullable
  public ListenableFuture<FingerprintValueStore> getFingerprintValueStore() {
    return store == null ? null : immediateFuture(store);
  }

  @Override
  @Nullable
  public ListenableFuture<GrpcAnalysisCacheClient> getAnalysisCacheClient() {
    return client == null ? null : immediateFuture(client);
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
    }
    shedAtReset = gets == null ? 0 : gets.shed();
    if (scheduler != null) {
      scheduler.shutdown();
      scheduler = null;
    }
    if (channel != null) {
      channel.shutdown();
      channel = null;
    }
    client = null;
    store = null;
    grpcStore = null;
    gets = null;
  }

  @Override
  public void blazeShutdown() {
    resetCommandState();
  }
}
