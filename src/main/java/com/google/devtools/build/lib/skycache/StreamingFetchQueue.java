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

import build.aspect.skycache.v1.AnalysisCacheGrpc.AnalysisCacheStub;
import build.aspect.skycache.v1.Entry;
import build.aspect.skycache.v1.FetchRequest;
import build.aspect.skycache.v1.FetchResponse;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Reads entries over one Fetch stream, on which the service sends what a requested entry
 * references along with the entry.
 *
 * <p>Bazel learns which nested values an entry needs only by deserializing it, so through {@link
 * BatchingGetQueue} every level of nesting is another request and another round trip, and a large
 * build has hundreds of thousands of reads in flight at once. Here the service walks the
 * references the writer recorded and pushes the closure, so by the time Bazel asks for a nested
 * value it is usually already here, or on its way.
 *
 * <p>How much that is worth depends on the size of the build. Reading one target of 155,000
 * configured targets, 546,000 entries, took the same 3.2 s of analysis either way, at up to 20 ms
 * of round trip. Reading the whole Canva monorepo, 10.7 million entries, from a service on the
 * same host took 73 s of analysis over the stream and 88 s in batches, against 328 s with no
 * cache.
 *
 * <p>Every key Bazel asks for is in one of three states:
 *
 * <ul>
 *   <li><b>delivered</b>: pushed and waiting in {@link #entries} as bytes. The read takes them and
 *       forgets the key, so the buffer holds only what has not been read yet.
 *   <li><b>awaited</b>: asked for and not here. The key is sent to the service, which answers with
 *       the entry, with a miss, or with nothing because the entry is already on its way.
 *   <li><b>unknown</b>: neither, which is also what a key read earlier looks like.
 * </ul>
 *
 * <p>The last is why the service reports keys it declined to send again. A read of a key that was
 * delivered and consumed, which Bazel does when it dropped the deserialized value, would otherwise
 * wait forever for an entry the service believes it has. Responses are ordered, so when the
 * service says a key was already sent and the read is still waiting, the entry is not coming, and
 * the read asks again with {@code force}. That keeps this class from remembering every key it has
 * ever handed over, which for a large build is tens of millions.
 *
 * <p>Expansion is only ever a prefetch. A reference the writer did not record costs one request
 * for that key, as every read does without this class.
 *
 * <p>If the service does not implement Fetch, every read goes to the fallback queue instead.
 */
final class StreamingFetchQueue implements ReadQueue {

  /** Upper bound on keys per request; the service rejects more than it is configured to take. */
  private final int maxBatchSize;

  private final AnalysisCacheStub stub;
  private final ReadQueue fallback;

  /** Sends requests; a single thread, because a stream's observer is not thread-safe. */
  private final Executor sender;

  /** Completes reads, so deserialization does not run on the thread the stream arrives on. */
  private final Executor completer;

  /**
   * By key, a delivered entry's bytes, as the {@link ByteString} they arrived in, or a {@link
   * Waiter}.
   *
   * <p>Keys are {@link PackedFingerprint}s where they are 16 bytes, which nearly all are, and
   * {@link ByteString}s otherwise.
   */
  private final ConcurrentHashMap<Object, Object> entries = new ConcurrentHashMap<>();

  private final Object lock = new Object();

  @GuardedBy("lock")
  private ArrayList<ByteString> outbox = new ArrayList<>();

  @GuardedBy("lock")
  private ArrayList<ByteString> forcedOutbox = new ArrayList<>();

  @GuardedBy("lock")
  private boolean flushScheduled;

  /** The open stream, or null before the first read and after a failure. Sender thread only. */
  @Nullable private StreamObserver<FetchRequest> requests;

  /** Set once the service is known not to implement Fetch. */
  private final AtomicBoolean unimplemented = new AtomicBoolean();

  private final AtomicLong requestsSent = new AtomicLong();
  private final AtomicLong keysRequested = new AtomicLong();
  private final AtomicLong entriesReceived = new AtomicLong();
  private final AtomicLong servedFromBuffer = new AtomicLong();
  private final AtomicLong forced = new AtomicLong();

  /** Bytes delivered and not yet read, and the most that has ever been. */
  private final AtomicLong bufferedBytes = new AtomicLong();

  private final AtomicLong peakBufferedBytes = new AtomicLong();

  /** A read waiting for its entry. Shared by every read of the key while it is awaited. */
  private static final class Waiter {
    private final SettableFuture<byte[]> value = SettableFuture.create();
    private final ByteString key;

    private Waiter(ByteString key) {
      this.key = key;
    }
  }

  StreamingFetchQueue(
      AnalysisCacheStub stub,
      ReadQueue fallback,
      int maxBatchSize,
      Executor sender,
      Executor completer) {
    this.stub = stub;
    this.fallback = fallback;
    this.maxBatchSize = maxBatchSize;
    this.sender = sender;
    this.completer = completer;
  }

  @Override
  public ListenableFuture<byte[]> enqueue(byte[] keyBytes) {
    if (unimplemented.get()) {
      return fallback.enqueue(keyBytes);
    }
    // One of: bytes taken from the buffer, an existing Waiter, or a new one to send for.
    Object[] found = new Object[1];
    boolean[] created = new boolean[1];
    entries.compute(
        mapKey(keyBytes),
        (unused, current) -> {
          if (current instanceof ByteString) {
            found[0] = current;
            return null; // Consumed: the buffer forgets it.
          }
          if (current == null) {
            current = new Waiter(ByteString.copyFrom(keyBytes));
            created[0] = true;
          }
          found[0] = current;
          return current;
        });
    if (found[0] instanceof ByteString bytes) {
      bufferedBytes.addAndGet(-bytes.size());
      servedFromBuffer.incrementAndGet();
      // Copied out here, on the reader's thread, rather than on the one the stream arrives on.
      return immediateFuture(bytes.toByteArray());
    }
    Waiter waiter = (Waiter) found[0];
    if (created[0]) {
      send(waiter.key, /* force= */ false);
    }
    return waiter.value;
  }

  private static Object mapKey(byte[] key) {
    return key.length == PackedFingerprint.BYTES
        ? PackedFingerprint.fromBytes(key)
        : ByteString.copyFrom(key);
  }

  private static Object mapKey(ByteString key) {
    return key.size() == PackedFingerprint.BYTES
        ? PackedFingerprint.fromBytes(key.toByteArray())
        : key;
  }

  /**
   * Queues a key for the next request.
   *
   * <p>There is no linger. A request leaves as soon as the sender is free, carrying whatever
   * accumulated while it was busy, so batches grow with load and an idle stream adds no latency.
   */
  private void send(ByteString key, boolean force) {
    boolean schedule;
    synchronized (lock) {
      (force ? forcedOutbox : outbox).add(key);
      schedule = !flushScheduled;
      flushScheduled = true;
    }
    if (schedule) {
      sender.execute(this::flush);
    }
  }

  /** Runs on the sender thread. */
  private void flush() {
    ArrayList<ByteString> keys;
    ArrayList<ByteString> forcedKeys;
    synchronized (lock) {
      keys = outbox;
      forcedKeys = forcedOutbox;
      outbox = new ArrayList<>();
      forcedOutbox = new ArrayList<>();
      flushScheduled = false;
    }
    if (unimplemented.get()) {
      // The stream was refused between these keys being queued and now.
      redirect(keys);
      redirect(forcedKeys);
      return;
    }
    try {
      if (requests == null) {
        requests = stub.fetch(new Responses());
      }
      write(keys, /* force= */ false);
      write(forcedKeys, /* force= */ true);
    } catch (RuntimeException e) {
      // The observer throws once the call has been cancelled or has failed.
      requests = null;
      failAwaited(keys, e);
      failAwaited(forcedKeys, e);
    }
  }

  private void write(List<ByteString> queued, boolean force) {
    // A key queued while the sender was busy may have been pushed since. Asking anyway is
    // harmless, the service only says so, but under load most of the queue is like that.
    ArrayList<ByteString> keys = new ArrayList<>(queued.size());
    for (ByteString key : queued) {
      if (entries.get(mapKey(key)) instanceof Waiter) {
        keys.add(key);
      }
    }
    for (int start = 0; start < keys.size(); start += maxBatchSize) {
      List<ByteString> batch = keys.subList(start, Math.min(keys.size(), start + maxBatchSize));
      requests.onNext(
          FetchRequest.newBuilder().addAllKeys(batch).setExpand(true).setForce(force).build());
      requestsSent.incrementAndGet();
      keysRequested.addAndGet(batch.size());
    }
  }

  /** Hands the reads of {@code keys} to the fallback queue. */
  private void redirect(List<ByteString> keys) {
    for (ByteString key : keys) {
      if (entries.remove(mapKey(key)) instanceof Waiter waiter) {
        waiter.value.setFuture(fallback.enqueue(key.toByteArray()));
      }
    }
  }

  private void failAwaited(List<ByteString> keys, Throwable t) {
    for (ByteString key : keys) {
      if (entries.remove(mapKey(key)) instanceof Waiter waiter) {
        waiter.value.setException(t);
      }
    }
  }

  /** The service's side of the stream. Calls are serial. */
  private final class Responses implements StreamObserver<FetchResponse> {
    @Override
    public void onNext(FetchResponse response) {
      entriesReceived.addAndGet(response.getEntriesCount());
      // Reads are completed off this thread, in one task per response: a completed read runs
      // deserialization, and running that here would let one core's worth of it gate the stream.
      ArrayList<Waiter> waiters = null;
      ArrayList<ByteString> values = null;
      long delivered = 0;
      Object[] previous = new Object[1];
      for (Entry entry : response.getEntriesList()) {
        ByteString value = entry.getValue();
        // Awaited: handed straight over below, so it never enters the buffer. Otherwise buffered.
        entries.compute(
            mapKey(entry.getKey()),
            (unused, current) -> {
              previous[0] = current;
              return current instanceof Waiter ? null : value;
            });
        if (previous[0] instanceof ByteString replaced) {
          delivered -= replaced.size(); // Sent twice, after a forced request.
        }
        if (previous[0] instanceof Waiter waiter) {
          if (waiters == null) {
            waiters = new ArrayList<>();
            values = new ArrayList<>();
          }
          waiters.add(waiter);
          values.add(value);
        } else {
          delivered += value.size();
        }
      }
      long buffered = bufferedBytes.addAndGet(delivered);
      peakBufferedBytes.accumulateAndGet(buffered, Math::max);
      for (ByteString key : response.getMissingList()) {
        if (entries.remove(mapKey(key)) instanceof Waiter waiter) {
          if (waiters == null) {
            waiters = new ArrayList<>();
            values = new ArrayList<>();
          }
          waiters.add(waiter);
          values.add(null);
        }
      }
      for (ByteString key : response.getAlreadySentList()) {
        // Everything sent before this response has been processed above or in an earlier call. A
        // read still waiting therefore missed the entry: it was consumed before the read began.
        if (entries.get(mapKey(key)) instanceof Waiter) {
          forced.incrementAndGet();
          send(key, /* force= */ true);
        }
      }
      if (waiters != null) {
        ArrayList<Waiter> completedWaiters = waiters;
        ArrayList<ByteString> completedValues = values;
        completer.execute(
            () -> {
              for (int i = 0; i < completedWaiters.size(); i++) {
                ByteString value = completedValues.get(i);
                completedWaiters.get(i).value.set(value == null ? null : value.toByteArray());
              }
            });
      }
    }

    @Override
    public void onError(Throwable t) {
      boolean refused = Status.fromThrowable(t).getCode() == Status.Code.UNIMPLEMENTED;
      if (refused) {
        unimplemented.set(true);
      }
      // On the sender thread, so the stream is not replaced while a flush is writing to it, and a
      // flush that follows opens a new one.
      sender.execute(
          () -> {
            requests = null;
            for (var entry : entries.entrySet()) {
              if (entry.getValue() instanceof Waiter waiter
                  && entries.remove(entry.getKey(), waiter)) {
                if (refused) {
                  waiter.value.setFuture(fallback.enqueue(waiter.key.toByteArray()));
                } else {
                  waiter.value.setException(t);
                }
              }
            }
          });
    }

    @Override
    public void onCompleted() {
      // The service ends the stream only after this side has, in close().
    }
  }

  @Override
  public long batchesSent() {
    return requestsSent.get() + fallback.batchesSent();
  }

  @Override
  public long shed() {
    return fallback.shed();
  }

  /** Entries that arrived before Bazel asked for them, and so cost it no round trip. */
  long servedFromBuffer() {
    return servedFromBuffer.get();
  }

  /** Keys sent to the service: the reads that the service's pushes did not anticipate. */
  long keysRequested() {
    return keysRequested.get();
  }

  /** Entries the service sent, asked for or pushed. */
  long entriesReceived() {
    return entriesReceived.get();
  }

  /** The most bytes that were ever delivered and waiting to be read. */
  long peakBufferedBytes() {
    return peakBufferedBytes.get();
  }

  /** Bytes delivered and never read: pushed for entries Bazel turned out not to need. */
  long unreadBytes() {
    return bufferedBytes.get();
  }

  /** Reads that asked a second time for an entry consumed earlier. */
  long forced() {
    return forced.get();
  }

  /** Ends the stream and drops whatever was pushed and never read. */
  @Override
  public void close() {
    sender.execute(
        () -> {
          if (requests != null) {
            try {
              requests.onCompleted();
            } catch (RuntimeException e) {
              // Already failed; nothing left to end.
            }
            requests = null;
          }
          entries.clear();
        });
    fallback.close();
  }
}
