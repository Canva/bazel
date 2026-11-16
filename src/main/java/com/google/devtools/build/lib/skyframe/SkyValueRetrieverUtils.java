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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.ActionLookupSummaryKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.skyframe.serialization.DependOnFutureShim;
import com.google.devtools.build.lib.skyframe.serialization.DependOnFutureShim.DefaultDependOnFutureShim;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.NoCachedData;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.Restart;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalContext;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalPhase;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievedValue;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.SerializableSkyKeyComputeState;
import com.google.devtools.build.lib.skyframe.serialization.analysis.AnalysisValueWithMtsv;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedTopLevelKey;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedTopLevelValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ExecutionValueCaching;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheReaderDepsProvider;
import com.google.devtools.build.lib.skyframe.serialization.analysis.SkycacheUploadClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.TopLevelAdmission;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.Version;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * A wrapper around {@link SkyValueRetriever} to handle Bazel-on-Skyframe specific logic, metrics
 * gathering, and error handling.
 */
public final class SkyValueRetrieverUtils {

  public static RetrievalResult retrieveRemoteSkyValue(
      SkyKey key,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps,
      Supplier<? extends SerializableSkyKeyComputeState> stateSupplier)
      throws InterruptedException {
    return retrieve(
        key, env, analysisCachingDeps, () -> env.getState(stateSupplier).getRetrievalContext());
  }

  /**
   * As {@link #retrieveRemoteSkyValue(SkyKey, Environment, RemoteAnalysisCacheReaderDepsProvider,
   * Supplier)}, for a function that looks up keys other than its own and so keeps the context of
   * each lookup itself, between restarts.
   */
  public static RetrievalResult retrieveRemoteSkyValue(
      SkyKey key,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps,
      RetrievalContext context)
      throws InterruptedException {
    return retrieve(key, env, analysisCachingDeps, () -> context);
  }

  /**
   * What asking the cache for a {@link CompletedTopLevelKey} found, ahead of the function that
   * acts on it.
   */
  record CompletedProbe(
      RetrievalResult result, ImmutableMap<RetrievalPhase, Long> phaseDurationMicros) {
    boolean valid() {
      return result instanceof RetrievedValue;
    }

    /**
     * The key the target was built with, if the record is valid and gives one: see {@link
     * CompletedTopLevelValue}.
     */
    @Nullable
    ActionLookupKey builtWith() {
      return result instanceof RetrievedValue(SkyValue value)
              && value instanceof CompletedTopLevelValue completed
          ? completed.builtWith()
          : null;
    }

    /**
     * What the record carries of a target whose tests all passed, a {@code ServedTest}, if it is
     * valid and carries it: see {@link CompletedTopLevelValue}.
     */
    @Nullable
    Object servedTest() {
      return result instanceof RetrievedValue(SkyValue value)
              && value instanceof CompletedTopLevelValue completed
          ? completed.servedTest()
          : null;
    }
  }

  /**
   * Asks the cache whether each of {@code keys} is recorded as built, without waiting for one
   * answer before asking for the next.
   *
   * <p>The answers are kept in {@code admission}, where the completion function of each key finds
   * its own, and counts it.
   *
   * @param contexts one for each key, kept by the caller between restarts
   * @return the answers in the order of the keys, or null if some are not known yet. The caller
   *     then returns null: what it waits for is registered.
   */
  @Nullable
  static CompletedProbe[] probeCompleted(
      List<CompletedTopLevelKey> keys,
      RetrievalContext[] contexts,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps,
      TopLevelAdmission admission)
      throws InterruptedException {
    CompletedProbe[] probes = new CompletedProbe[keys.size()];
    boolean pending = false;
    DependOnFutureShim shim = new SeveralFuturesShim(env);
    for (int i = 0; i < probes.length; i++) {
      CompletedTopLevelKey key = keys.get(i);
      if (admission.probed(key) instanceof CompletedProbe known) {
        probes[i] = known;
        continue;
      }
      RetrievalContext context = contexts[i];
      RetrievalResult result =
          retrieve(key, env, analysisCachingDeps, () -> context, shim, /* record= */ false);
      if (result == Restart.RESTART) {
        pending = true;
        continue;
      }
      probes[i] = new CompletedProbe(result, context.getPhaseDurationMicros());
      if (!(result instanceof NoCachedData(MissReason reason)
          && reason == MissReason.MISS_REASON_NOT_ATTEMPTED)) {
        admission.recordProbe(key, probes[i], probes[i].valid());
      }
    }
    return pending ? null : probes;
  }

  /**
   * Looks {@code key} up outside the evaluation of any node, for a value whose deserialization
   * looks nothing up in the graph. The future holds a miss if the lookup failed in any way.
   */
  static ListenableFuture<RetrievalResult> retrieveDetached(
      SkyKey key, RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps)
      throws InterruptedException {
    SkyValueRetriever retriever = analysisCachingDeps.getSkyValueRetriever();
    RemoteAnalysisCacheClient client = analysisCachingDeps.getAnalysisCacheClient();
    if (retriever == null
        || client == null
        || analysisCachingDeps.shouldBailOutOnMissingFingerprint()) {
      return Futures.immediateFuture(new NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED));
    }
    DetachedRetrieval retrieval = new DetachedRetrieval(key, retriever, client);
    retrieval.run();
    return retrieval.result;
  }

  /** Takes a retrieval through its steps, each when the future the last one waited for is done. */
  private static final class DetachedRetrieval implements Runnable, DependOnFutureShim {
    private final SkyKey key;
    private final SkyValueRetriever retriever;
    private final RemoteAnalysisCacheClient client;
    private final RetrievalContext context = new RetrievalContext();
    private final SettableFuture<RetrievalResult> result = SettableFuture.create();

    /** What the step in progress waits for. Only one step runs at a time. */
    @Nullable private ListenableFuture<?> waitingFor;

    private DetachedRetrieval(
        SkyKey key, SkyValueRetriever retriever, RemoteAnalysisCacheClient client) {
      this.key = key;
      this.retriever = retriever;
      this.client = client;
    }

    @Override
    public ObservedFutureStatus dependOnFuture(ListenableFuture<?> future) {
      if (future.isDone()) {
        return ObservedFutureStatus.DONE;
      }
      waitingFor = future;
      return ObservedFutureStatus.NOT_DONE;
    }

    @Override
    public void run() {
      try {
        waitingFor = null;
        // Without an environment: a value that needs one is not for this.
        RetrievalResult step = retriever.tryRetrieve(/* env= */ null, this, client, key, context);
        if (step != Restart.RESTART) {
          result.set(step);
          return;
        }
        ListenableFuture<?> next = waitingFor;
        if (next == null) {
          result.set(new NoCachedData(MissReason.MISS_REASON_UNSPECIFIED));
          return;
        }
        next.addListener(this, ForkJoinPool.commonPool());
      } catch (SerializationException | RuntimeException e) {
        result.set(new NoCachedData(MissReason.MISS_REASON_UNSPECIFIED));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        result.set(new NoCachedData(MissReason.MISS_REASON_UNSPECIFIED));
      } finally {
        if (result.isDone()) {
          context.getState().cleanupSerializationState();
        }
      }
    }
  }

  /**
   * Returns how this command orders its top-level keys, or null if it does not: it is not reading
   * records of what was built, or the function is only being run to report an error.
   */
  @Nullable
  static TopLevelAdmission topLevelAdmission(
      @Nullable Supplier<RemoteAnalysisCacheReaderDepsProvider> analysisCachingDeps,
      Environment env) {
    TopLevelAdmission admission = ExecutionValueCaching.topLevelAdmission();
    if (admission == null || analysisCachingDeps == null || env.inErrorBubbling()) {
      return null;
    }
    RemoteAnalysisCacheReaderDepsProvider deps = analysisCachingDeps.get();
    return deps.mode().isRetrievalEnabled() && !deps.getSkycacheAnalysisOnly() ? admission : null;
  }

  /**
   * Has a top-level key take its turn to fetch its value: at once if it has work to do, and
   * otherwise when {@code admission} lets it through. See {@link TopLevelAdmission}.
   *
   * @param built whether the cache records as built everything the key is going to be asked for,
   *     so that it has no work to do
   * @return false if the caller is to return null and be restarted
   */
  static boolean takeTurn(
      ActionLookupKey topLevelKey, boolean built, Environment env, TopLevelAdmission admission) {
    if (!built) {
      admission.noteHasWork(topLevelKey);
      return true;
    }
    env.dependOnFuture(admission.admit(topLevelKey));
    return !env.valuesMissing();
  }

  /**
   * Lets a function wait for several futures at once. {@link DefaultDependOnFutureShim} refuses a
   * second while the first is pending.
   *
   * <p>A future that completes while it is being registered is reported as done, and may have been
   * registered all the same, so a caller checks {@link Environment#valuesMissing} before it
   * returns a value.
   */
  private static final class SeveralFuturesShim implements DependOnFutureShim {
    private final Environment env;

    private SeveralFuturesShim(Environment env) {
      this.env = env;
    }

    @Override
    public ObservedFutureStatus dependOnFuture(ListenableFuture<?> future) {
      env.dependOnFuture(future);
      return future.isDone() ? ObservedFutureStatus.DONE : ObservedFutureStatus.NOT_DONE;
    }
  }

  private static RetrievalResult retrieve(
      SkyKey key,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps,
      Supplier<RetrievalContext> contextSupplier)
      throws InterruptedException {
    return retrieve(
        key,
        env,
        analysisCachingDeps,
        contextSupplier,
        new DefaultDependOnFutureShim(env),
        /* record= */ true);
  }

  private static RetrievalResult retrieve(
      SkyKey key,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps,
      Supplier<RetrievalContext> contextSupplier,
      DependOnFutureShim futuresShim,
      boolean record)
      throws InterruptedException {
    if (env.inErrorBubbling()) {
      // Remote retrieval during error bubbling causes incorrect error propagation. See b/449016469.
      return new NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED);
    }

    if (analysisCachingDeps.shouldBailOutOnMissingFingerprint()) {
      return new NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED);
    }

    Label label =
        switch (key) {
          case ActionLookupKey alk -> alk.getLabel();
          case ActionLookupData ald -> ald.getLabel();
          case ActionLookupSummaryKey summaryKey -> summaryKey.argument().getLabel();
          case Artifact artifact -> artifact.getOwnerLabel();
          case CompletedTopLevelKey completed -> completed.owner().getLabel();
          default -> throw new IllegalStateException("unexpected key: " + key.getCanonicalName());
        };

    if (label == null) {
      // If there's no label, there's no cached data.
      return new NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED);
    }

    SkyValueRetriever retriever = analysisCachingDeps.getSkyValueRetriever();
    RemoteAnalysisCacheClient client = analysisCachingDeps.getAnalysisCacheClient();
    if (retriever == null || client == null) {
      return new NoCachedData(MissReason.MISS_REASON_NOT_ATTEMPTED);
    }

    RetrievalResult retrievalResult = null;
    RetrievalContext state = contextSupplier.get();
    if (state.isInitialQuery()) {
      state.setStartTimestampNanos(System.nanoTime());
    }
    try {
      retrievalResult =
          retriever.tryRetrieve(env, futuresShim, client, key, state);
      retrievalResult =
          maybeUnwrapVersionedSkyValue(key, retrievalResult, env, analysisCachingDeps);
      if (record
          && (retrievalResult instanceof RetrievedValue
              || retrievalResult instanceof NoCachedData)) {
        analysisCachingDeps.recordRetrievalResult(
            retrievalResult, key, state.getPhaseDurationMicros());
      }
    } catch (SerializationException e) {
      // Don't crash the build if deserialization failed. Gracefully fallback to local evaluation.
      analysisCachingDeps.recordSerializationException(e, key, state.getPhaseDurationMicros());
      retrievalResult = new NoCachedData(e.getReason());
    } finally {
      if (retrievalResult == Restart.RESTART) {
        state.addRestart();
      }
    }

    if (retrievalResult instanceof RetrievedValue(SkyValue v)
        && !(v instanceof DeserializedSkyValue)) {
      throw new IllegalStateException(
          "deserialized SkyValue of type "
              + v.getClass().getCanonicalName()
              + " does not implement DeserializedSkyValue. Try using"
              + " @AutoCodec(deserializedInterface = DeserializedSkyValue.class)");
    }

    return retrievalResult;
  }

  private static RetrievalResult maybeUnwrapVersionedSkyValue(
      SkyKey key,
      RetrievalResult retrievalResult,
      Environment env,
      RemoteAnalysisCacheReaderDepsProvider analysisCachingDeps) {
    if (!(key instanceof ActionLookupKey)
        || !(retrievalResult instanceof RetrievedValue(SkyValue value))) {
      return retrievalResult;
    }

    if (value instanceof AnalysisValueWithMtsv(SkyValue innerValue, Version mtsv)) {
      if (analysisCachingDeps.getSkycacheAnalysisOnly()
          && env.getMaxTransitiveSourceVersionSoFar() != null) {
        env.injectVersion(mtsv);
      }
      return new RetrievedValue(innerValue);
    }
    if (analysisCachingDeps.getSkycacheAnalysisOnly()) {
      return new NoCachedData(MissReason.MISS_REASON_MISSING_MTSV);
    }
    return retrievalResult;
  }

  public static void tryUploadAsync(
      RemoteAnalysisCacheReaderDepsProvider cachingDeps,
      SkyKey key,
      SkyValue value,
      Environment env)
      throws InterruptedException {
    if (!cachingDeps.mode().isAsyncUpload()) {
      return;
    }

    SkycacheUploadClient uploadClient = cachingDeps.getSkycacheUploadClient();
    // TODO(b/527929697): Handle null uploadClient properly (e.g. fail the build cleanly).
    checkNotNull(uploadClient).tryUpload(key, value, env);
  }

  private SkyValueRetrieverUtils() {}
}
