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
import build.aspect.skycache.v1.PutRequest;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coalesces single-entry writes into batched Put RPCs.
 *
 * <p>{@link com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore#put} is a
 * one-entry call, and an upload of a 212,000-target repository makes roughly 600,000 of them. One
 * RPC each is not a slow design, it is an unusable one, so writes accumulate here and leave in
 * groups.
 *
 * <p>A batch departs when it is full or when the linger expires, whichever is first. The linger
 * matters at the tail of a build, where the last partial batch has nothing following it to push it
 * out.
 */
final class BatchingPutQueue {

  private final AnalysisCacheFutureStub stub;
  private final int maxBatchSize;
  private final long lingerMillis;
  private final long deadlineMillis;
  private final ScheduledExecutorService scheduler;

  private final Object lock = new Object();
  private List<PendingPut> pending = new ArrayList<>();

  private final AtomicLong batchesSent = new AtomicLong();

  /** A write waiting for the batch it belongs to to be sent. */
  private record PendingPut(Entry entry, SettableWriteStatus done) {}

  BatchingPutQueue(
      AnalysisCacheFutureStub stub,
      int maxBatchSize,
      long lingerMillis,
      long deadlineMillis,
      ScheduledExecutorService scheduler) {
    this.stub = stub;
    this.maxBatchSize = maxBatchSize;
    this.lingerMillis = lingerMillis;
    this.deadlineMillis = deadlineMillis;
    this.scheduler = scheduler;
  }

  /** Enqueues an entry, returning a future that completes when its batch has been acknowledged. */
  SettableWriteStatus enqueue(byte[] key, byte[] value) {
    SettableWriteStatus done = new SettableWriteStatus();
    Entry entry =
        Entry.newBuilder()
            .setKey(com.google.protobuf.ByteString.copyFrom(key))
            .setValue(com.google.protobuf.ByteString.copyFrom(value))
            .build();

    List<PendingPut> toSend = null;
    boolean scheduleLinger = false;
    synchronized (lock) {
      pending.add(new PendingPut(entry, done));
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
    return done;
  }

  /** Sends whatever is queued. Safe to call when nothing is. */
  void flush() {
    List<PendingPut> toSend;
    synchronized (lock) {
      if (pending.isEmpty()) {
        return;
      }
      toSend = takeLocked();
    }
    send(toSend);
  }

  private List<PendingPut> takeLocked() {
    List<PendingPut> taken = pending;
    pending = new ArrayList<>();
    return taken;
  }

  private void send(List<PendingPut> batch) {
    batchesSent.incrementAndGet();
    PutRequest.Builder request = PutRequest.newBuilder();
    for (PendingPut put : batch) {
      request.addEntries(put.entry());
    }
    Futures.addCallback(
        stub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS).put(request.build()),
        new FutureCallback<>() {
          @Override
          public void onSuccess(build.aspect.skycache.v1.PutResponse response) {
            // The service counts novel entries per batch, not per entry, so a partly novel batch
            // reports every entry as novel. Over-reporting is safe; Bazel only ORs these together.
            boolean novel = response.getStored() != 0;
            for (PendingPut put : batch) {
              put.done().markSuccess(novel);
            }
          }

          @Override
          public void onFailure(Throwable t) {
            // Fail every write in the batch: the serializer aggregates these and reports the count,
            // so a silent drop would present a partial upload as a complete one.
            for (PendingPut put : batch) {
              put.done().failWith(t);
            }
          }
        },
        MoreExecutors.directExecutor());
  }

  /** Number of Put RPCs actually issued, as against the number of entries handed over. */
  long batchesSent() {
    return batchesSent.get();
  }
}
