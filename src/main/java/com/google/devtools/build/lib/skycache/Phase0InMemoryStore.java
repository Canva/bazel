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

import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.util.Bucket;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link FingerprintValueStore} held in server memory, selected with {@code
 * --experimental_remote_analysis_cache=inmemory://}.
 *
 * <p>Exists to let an upload run to completion with no backend, so that serialization itself can be
 * measured. Entries live as long as the server's analysis cache supplier and are never evicted.
 *
 * <p>{@link FingerprintValueStore#inMemoryStore} does the same but is package-private and keeps no
 * statistics, hence the copy.
 */
final class Phase0InMemoryStore implements FingerprintValueStore {

  private final ConcurrentHashMap<KeyBytesProvider, byte[]> entries = new ConcurrentHashMap<>();

  private final AtomicLong valueBytesReceived = new AtomicLong();
  private final AtomicLong valueBytesSent = new AtomicLong();
  private final AtomicLong keyBytesSent = new AtomicLong();
  private final AtomicLong entriesWritten = new AtomicLong();
  private final AtomicLong entriesFound = new AtomicLong();
  private final AtomicLong entriesNotFound = new AtomicLong();

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    keyBytesSent.addAndGet(fingerprint.toBytes().length);
    valueBytesSent.addAndGet(serializedBytes.length);
    entriesWritten.incrementAndGet();
    return immediateWriteStatus(entries.putIfAbsent(fingerprint, serializedBytes) == null);
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
    byte[] value = entries.get(fingerprint);
    if (value == null) {
      entriesNotFound.incrementAndGet();
      return immediateFailedFuture(new MissingFingerprintValueException(fingerprint));
    }
    entriesFound.incrementAndGet();
    valueBytesReceived.addAndGet(value.length);
    return immediateFuture(value);
  }

  @Override
  public Stats getStats() {
    long received = valueBytesReceived.get();
    long sent = valueBytesSent.get();
    long keys = keyBytesSent.get();
    long written = entriesWritten.get();
    long found = entriesFound.get();
    long notFound = entriesNotFound.get();
    return new Stats() {
      @Override
      public long valueBytesReceived() {
        return received;
      }

      @Override
      public long valueBytesSent() {
        return sent;
      }

      @Override
      public long keyBytesSent() {
        return keys;
      }

      @Override
      public long entriesWritten() {
        return written;
      }

      @Override
      public long entriesFound() {
        return found;
      }

      @Override
      public long entriesNotFound() {
        return notFound;
      }

      @Override
      public long getBatches() {
        return 0;
      }

      @Override
      public long setBatches() {
        return 0;
      }

      @Override
      public List<Bucket> getLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> setLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> getBatchLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> setBatchLatencyMicros() {
        return ImmutableList.of();
      }
    };
  }
}
