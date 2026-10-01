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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.util.Bucket;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** A {@link FingerprintValueStore} backed by the analysis cache service. */
final class GrpcFingerprintValueStore implements FingerprintValueStore {

  private final BatchingGetQueue gets;
  private final BatchingPutQueue puts;

  private final AtomicLong valueBytesReceived = new AtomicLong();
  private final AtomicLong valueBytesSent = new AtomicLong();
  private final AtomicLong keyBytesSent = new AtomicLong();
  private final AtomicLong entriesWritten = new AtomicLong();
  private final AtomicLong entriesFound = new AtomicLong();
  private final AtomicLong entriesNotFound = new AtomicLong();

  GrpcFingerprintValueStore(BatchingGetQueue gets, BatchingPutQueue puts) {
    this.gets = gets;
    this.puts = puts;
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    byte[] keyBytes = fingerprint.toBytes();
    keyBytesSent.addAndGet(keyBytes.length);
    valueBytesSent.addAndGet(serializedBytes.length);
    entriesWritten.incrementAndGet();
    return puts.enqueue(keyBytes, serializedBytes);
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) {
    return Futures.transform(
        gets.enqueue(fingerprint.toBytes()),
        value -> {
          if (value == null) {
            entriesNotFound.incrementAndGet();
            // The contract is an exception, not an empty array: the retriever distinguishes "absent"
            // from "present and empty".
            throw new MissingEntryException(fingerprint);
          }
          entriesFound.incrementAndGet();
          valueBytesReceived.addAndGet(value.length);
          return value;
        },
        MoreExecutors.directExecutor());
  }

  /**
   * Carries {@link MissingFingerprintValueException} out of a transform.
   *
   * <p>The declared exception is checked, which a {@code Function} cannot throw, so it travels
   * wrapped and is unwrapped by the caller's root-cause inspection.
   */
  private static final class MissingEntryException extends RuntimeException {
    MissingEntryException(KeyBytesProvider fingerprint) {
      super(new MissingFingerprintValueException(fingerprint));
    }
  }

  /** Flushes queued writes. Called once the serializer has handed over every entry. */
  void flush() {
    puts.flush();
  }

  @Override
  public Stats getStats() {
    long valueBytesReceived = this.valueBytesReceived.get();
    long valueBytesSent = this.valueBytesSent.get();
    long keyBytesSent = this.keyBytesSent.get();
    long entriesWritten = this.entriesWritten.get();
    long entriesFound = this.entriesFound.get();
    long entriesNotFound = this.entriesNotFound.get();
    long getBatches = gets.batchesSent();
    long setBatches = puts.batchesSent();
    // Latency is not tracked; the service's own metrics carry it.
    return new Stats() {
      @Override
      public long valueBytesReceived() {
        return valueBytesReceived;
      }

      @Override
      public long valueBytesSent() {
        return valueBytesSent;
      }

      @Override
      public long keyBytesSent() {
        return keyBytesSent;
      }

      @Override
      public long entriesWritten() {
        return entriesWritten;
      }

      @Override
      public long entriesFound() {
        return entriesFound;
      }

      @Override
      public long entriesNotFound() {
        return entriesNotFound;
      }

      @Override
      public long getBatches() {
        return getBatches;
      }

      @Override
      public long setBatches() {
        return setBatches;
      }

      @Override
      public List<Bucket> getLatencyMicros() {
        return List.of();
      }

      @Override
      public List<Bucket> setLatencyMicros() {
        return List.of();
      }

      @Override
      public List<Bucket> getBatchLatencyMicros() {
        return List.of();
      }

      @Override
      public List<Bucket> setBatchLatencyMicros() {
        return List.of();
      }
    };
  }
}
