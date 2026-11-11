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

import build.aspect.skycache.v1.AnalysisCacheGrpc.AnalysisCacheFutureStub;
import build.aspect.skycache.v1.Entry;
import build.aspect.skycache.v1.GetRequest;
import build.aspect.skycache.v1.GetResponse;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coalesces single-key reads into batched Get RPCs.
 *
 * <p>Both of Bazel's read paths fetch one key at a time, which on a large download means tens of
 * thousands of round trips whose latency is paid serially against Skyframe's own fan-out. Both
 * share one queue: a response is the stored bytes either way, and interleaving them makes the
 * batches fuller than two queues filling in parallel would.
 *
 * <p>A batch departs when it reaches {@code maxBatchSize} or when a timer started by its first key
 * expires. Waiting for a batch to fill trades latency for round trips, so the linger here is much
 * shorter than for writes — a read has a Skyframe node blocked behind it, where a write has nobody.
 *
 * <p>Batching on the return of an outstanding RPC instead, as mainline's option surface suggests it
 * does, was measured and is worse here: Skyframe's reads are a closed loop, so the queue cannot
 * fill while the RPC that would unblock its producers is outstanding. At 4,988 lookups it issued
 * 1.6x to 5x more RPCs than the timer at every injected latency, with no wall-time difference.
 */
final class BatchingGetQueue implements ReadQueue {

  private final AnalysisCacheFutureStub stub;
  private final int maxBatchSize;
  private final long lingerMillis;
  private final int maxInFlight;
  private final long deadlineMillis;
  private final ScheduledExecutorService scheduler;

  private final AtomicInteger inFlight = new AtomicInteger();

  private final Object lock = new Object();
  private List<PendingGet> pending = new ArrayList<>();

  private final AtomicLong batchesSent = new AtomicLong();
  private final AtomicLong shed = new AtomicLong();

  /** A read waiting for the batch it belongs to to come back. */
  private record PendingGet(ByteString key, SettableFuture<byte[]> value) {}

  BatchingGetQueue(
      AnalysisCacheFutureStub stub,
      int maxBatchSize,
      long lingerMillis,
      int maxInFlight,
      long deadlineMillis,
      ScheduledExecutorService scheduler) {
    this.stub = stub;
    this.maxBatchSize = maxBatchSize;
    this.lingerMillis = lingerMillis;
    this.maxInFlight = maxInFlight;
    this.deadlineMillis = deadlineMillis;
    this.scheduler = scheduler;
  }

  /**
   * Enqueues a key.
   *
   * <p>Never declined, however many reads are in flight. This queue used to answer a read over
   * the cap as a miss, whatever the read was for. On a read of the Canva monorepo that declined
   * 2,149 reads of invalidation data, each remembered as missing by every entry that shared it,
   * and 3.1 million entries were rejected for it: the build was slower than with no cache. The
   * cap is now for the caller of a top-level lookup to apply, through {@link #saturated}.
   *
   * @return the stored value, or {@code null} if the service has no entry for the key. Callers
   *     translate absence into whatever their own contract expects.
   */
  @Override
  public SettableFuture<byte[]> enqueue(byte[] key) {
    SettableFuture<byte[]> value = SettableFuture.create();
    List<PendingGet> toSend = null;
    boolean scheduleLinger = false;
    synchronized (lock) {
      pending.add(new PendingGet(ByteString.copyFrom(key), value));
      if (pending.size() >= maxBatchSize) {
        toSend = takeLocked();
      } else if (pending.size() == 1) {
        scheduleLinger = true;
      }
    }
    if (toSend != null) {
      send(toSend);
    } else if (scheduleLinger) {
      var unused = scheduler.schedule(this::flush, lingerMillis, TimeUnit.MILLISECONDS);
    }
    return value;
  }

  /**
   * Sends whatever is queued. Safe to call when nothing is.
   *
   * <p>Takes exactly one batch and stops. Looping until the queue is empty instead would keep
   * catching the keys arriving concurrently behind it, which on a fast producer shrinks every batch
   * to a handful of keys: measured at 37 RPCs before and 316 after.
   */
  void flush() {
    List<PendingGet> toSend;
    synchronized (lock) {
      if (pending.isEmpty()) {
        return;
      }
      toSend = takeLocked();
    }
    send(toSend);
  }

  private List<PendingGet> takeLocked() {
    List<PendingGet> taken = pending;
    pending = new ArrayList<>();
    return taken;
  }

  @Override
  public boolean saturated() {
    return maxInFlight > 0 && inFlight.get() >= maxInFlight;
  }

  @Override
  public void noteShed() {
    shed.incrementAndGet();
  }

  private void send(List<PendingGet> batch) {
    batchesSent.incrementAndGet();
    inFlight.addAndGet(batch.size());
    GetRequest.Builder request = GetRequest.newBuilder();
    // One key, one request entry, however many waiters: a shared value reached from two parents is
    // asked for twice within a batch, and sending the key twice would pay for it twice. Every
    // waiter is completed from the same response entry below.
    Set<ByteString> requested = new HashSet<>(batch.size() * 2);
    for (PendingGet get : batch) {
      if (requested.add(get.key())) {
        request.addKeys(get.key());
      }
    }
    Futures.addCallback(
        stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS).get(request.build()),
        new FutureCallback<GetResponse>() {
          @Override
          public void onSuccess(GetResponse response) {
            inFlight.addAndGet(-batch.size());
            Map<ByteString, byte[]> found = new HashMap<>(response.getEntriesCount() * 2);
            for (Entry entry : response.getEntriesList()) {
              found.put(entry.getKey(), entry.getValue().toByteArray());
            }
            for (PendingGet get : batch) {
              // Absent means the service has no entry: a miss, not a failure.
              get.value().set(found.get(get.key()));
            }
          }

          @Override
          public void onFailure(Throwable t) {
            inFlight.addAndGet(-batch.size());
            for (PendingGet get : batch) {
              get.value().setException(t);
            }
          }
        },
        MoreExecutors.directExecutor());
  }

  /** Number of RPCs actually issued, as against the number of keys asked for. */
  @Override
  public long batchesSent() {
    return batchesSent.get();
  }

  /** Reads answered as misses because the in-flight cap was reached. */
  @Override
  public long shed() {
    return shed.get();
  }
}
