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

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/**
 * Writes to the service and to each of its mirrors, and reads from the service alone.
 *
 * <p>A write completes when every copy has been acknowledged, so the snapshot is recorded only
 * after all of them are. A mirror's failure is kept from the build: the copy in the service and the
 * other mirrors are as good as ever. It is counted instead, and {@link SnapshotIndex#mirrored}
 * records no snapshot in a mirror that lost a write.
 */
final class MirroredFingerprintValueStore implements FingerprintValueStore {

  /** One other service, written through its own channel and batching. */
  static final class Mirror {
    private final String target;
    private final BatchingPutQueue puts;
    private final SnapshotIndex index;
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    Mirror(String target, BatchingPutQueue puts, SnapshotIndex index) {
      this.target = target;
      this.puts = puts;
      this.index = index;
    }

    String target() {
      return target;
    }

    SnapshotIndex index() {
      return index;
    }

    /** Sends whatever is queued. */
    void flush() {
      puts.flush();
    }

    /** Returns why this mirror lost writes, or null if it lost none. */
    @Nullable
    String failure() {
      long failed = failedWrites.get();
      Throwable first = firstFailure.get();
      return failed == 0
          ? null
          : String.format("%d writes failed, the first with %s", failed, first.getMessage());
    }

    private WriteStatus put(byte[] key, byte[] value) {
      SettableWriteStatus done = new SettableWriteStatus();
      WriteStatus sent = puts.enqueue(key, value);
      sent.addListener(
          () -> {
            try {
              done.markSuccess(Futures.getDone(sent));
            } catch (ExecutionException e) {
              failedWrites.incrementAndGet();
              firstFailure.compareAndSet(null, e.getCause());
              done.markSuccess(false);
            }
          },
          directExecutor());
      return done;
    }
  }

  private final FingerprintValueStore primary;
  private final ImmutableList<Mirror> mirrors;

  MirroredFingerprintValueStore(FingerprintValueStore primary, ImmutableList<Mirror> mirrors) {
    this.primary = primary;
    this.mirrors = mirrors;
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    var statuses = new ArrayList<WriteStatus>(mirrors.size() + 1);
    statuses.add(primary.put(fingerprint, serializedBytes));
    byte[] key = fingerprint.toBytes();
    for (Mirror mirror : mirrors) {
      statuses.add(mirror.put(key, serializedBytes));
    }
    return WriteStatuses.aggregateWriteStatuses(statuses);
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    return primary.get(fingerprint);
  }

  @Override
  public Stats getStats() {
    return primary.getStats();
  }
}
