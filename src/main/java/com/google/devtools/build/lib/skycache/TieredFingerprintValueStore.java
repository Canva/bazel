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

import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import com.google.devtools.build.lib.util.Bucket;
import java.io.IOException;
import java.util.List;

/**
 * Reads the local store first and the service on a miss, keeping what the service answered; writes
 * only the local store.
 *
 * <p>A developer's machine reads what the priming pipeline uploaded and what its own earlier
 * builds wrote, and shares only with itself: its uploads are of a modified working tree. Keeping
 * service answers locally makes a server restart, or a second worktree, read the disk instead.
 */
final class TieredFingerprintValueStore implements FingerprintValueStore {

  private final FingerprintValueStore local;
  private final FingerprintValueStore remote;

  TieredFingerprintValueStore(FingerprintValueStore local, FingerprintValueStore remote) {
    this.local = local;
    this.remote = remote;
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    return local.put(fingerprint, serializedBytes);
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    return Futures.catchingAsync(
        local.get(fingerprint),
        Exception.class,
        e -> {
          if (!(getRootCause(e) instanceof MissingFingerprintValueException)) {
            throw e;
          }
          return Futures.transform(
              remote.get(fingerprint),
              value -> {
                // Same key, same bytes: a content address. A failure only costs the next read.
                var unused = local.put(fingerprint, value);
                return value;
              },
              directExecutor());
        },
        directExecutor());
  }

  /** The local store's writes and the service's reads, as only those touch storage beyond disk. */
  @Override
  public Stats getStats() {
    Stats localStats = local.getStats();
    Stats remoteStats = remote.getStats();
    return new Stats() {
      @Override
      public long valueBytesReceived() {
        return localStats.valueBytesReceived() + remoteStats.valueBytesReceived();
      }

      @Override
      public long valueBytesSent() {
        return localStats.valueBytesSent();
      }

      @Override
      public long keyBytesSent() {
        return localStats.keyBytesSent() + remoteStats.keyBytesSent();
      }

      @Override
      public long entriesWritten() {
        return localStats.entriesWritten();
      }

      @Override
      public long entriesFound() {
        return localStats.entriesFound() + remoteStats.entriesFound();
      }

      @Override
      public long entriesNotFound() {
        return remoteStats.entriesNotFound();
      }

      @Override
      public long getBatches() {
        return remoteStats.getBatches();
      }

      @Override
      public long setBatches() {
        return localStats.setBatches();
      }

      @Override
      public List<Bucket> getLatencyMicros() {
        return remoteStats.getLatencyMicros();
      }

      @Override
      public List<Bucket> setLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> getBatchLatencyMicros() {
        return remoteStats.getBatchLatencyMicros();
      }

      @Override
      public List<Bucket> setBatchLatencyMicros() {
        return ImmutableList.of();
      }
    };
  }
}
