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
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.LookupSnapshotResponse;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.RecordSnapshotResponse;
import com.canva.skycache.v1.SnapshotIndexGrpc.SnapshotIndexFutureStub;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Where uploads are recorded: the service's {@code SnapshotIndex}, or its counterpart in a local
 * store, with the same semantics.
 */
interface SnapshotIndex {

  ListenableFuture<RecordSnapshotResponse> record(RecordSnapshotRequest request);

  /** Answers the newest snapshot for the install and configuration at or before the ordinal. */
  ListenableFuture<LookupSnapshotResponse> lookup(LookupSnapshotRequest request);

  /** The service's index, each call bounded by {@link SkycacheMetadataParams#TIMEOUT}. */
  static SnapshotIndex of(SnapshotIndexFutureStub stub) {
    long timeoutMillis = SkycacheMetadataParams.TIMEOUT.toMillis();
    return new SnapshotIndex() {
      @Override
      public ListenableFuture<RecordSnapshotResponse> record(RecordSnapshotRequest request) {
        return stub.withDeadlineAfter(timeoutMillis, MILLISECONDS).record(request);
      }

      @Override
      public ListenableFuture<LookupSnapshotResponse> lookup(LookupSnapshotRequest request) {
        return stub.withDeadlineAfter(timeoutMillis, MILLISECONDS).lookup(request);
      }
    };
  }

  /**
   * Records in {@code primary} and in every mirror that took all of the upload's writes, and looks
   * up in {@code primary} alone, which is all a download reads.
   *
   * <p>A record fails if the primary's does, or, after recording everywhere else it can, if any
   * mirror was skipped or failed; either is worth a warning.
   */
  static SnapshotIndex mirrored(
      SnapshotIndex primary, ImmutableList<MirroredFingerprintValueStore.Mirror> mirrors) {
    return new SnapshotIndex() {
      @Override
      public ListenableFuture<RecordSnapshotResponse> record(RecordSnapshotRequest request) {
        ListenableFuture<RecordSnapshotResponse> primaryRecord = primary.record(request);
        var all = new ArrayList<ListenableFuture<?>>();
        all.add(primaryRecord);
        var problems = new ArrayList<String>();
        var mirrorRecords = new LinkedHashMap<String, ListenableFuture<RecordSnapshotResponse>>();
        for (MirroredFingerprintValueStore.Mirror mirror : mirrors) {
          String failure = mirror.failure();
          if (failure != null) {
            problems.add(mirror.target() + " (not recorded: " + failure + ")");
          } else {
            var record = mirror.index().record(request);
            mirrorRecords.put(mirror.target(), record);
            all.add(record);
          }
        }
        return Futures.whenAllComplete(all)
            .call(
                () -> {
                  RecordSnapshotResponse response = Futures.getDone(primaryRecord);
                  for (var record : mirrorRecords.entrySet()) {
                    try {
                      Futures.getDone(record.getValue());
                    } catch (ExecutionException e) {
                      problems.add(record.getKey() + " (" + e.getCause().getMessage() + ")");
                    }
                  }
                  if (!problems.isEmpty()) {
                    throw new IOException(
                        "recorded in the analysis cache service but not in mirrors "
                            + String.join(", ", problems));
                  }
                  return response;
                },
                directExecutor());
      }

      @Override
      public ListenableFuture<LookupSnapshotResponse> lookup(LookupSnapshotRequest request) {
        return primary.lookup(request);
      }
    };
  }

  /**
   * Records in {@code local}, where this command's uploads go, and answers from whichever of the
   * two has the newer snapshot, since entries are read from both.
   */
  static SnapshotIndex tiered(SnapshotIndex local, SnapshotIndex remote) {
    return new SnapshotIndex() {
      @Override
      public ListenableFuture<RecordSnapshotResponse> record(RecordSnapshotRequest request) {
        return local.record(request);
      }

      @Override
      public ListenableFuture<LookupSnapshotResponse> lookup(LookupSnapshotRequest request) {
        ListenableFuture<LookupSnapshotResponse> fromLocal = local.lookup(request);
        ListenableFuture<LookupSnapshotResponse> fromRemote = remote.lookup(request);
        return Futures.whenAllComplete(fromLocal, fromRemote)
            .call(
                () -> {
                  LookupSnapshotResponse localResponse = Futures.getDone(fromLocal);
                  LookupSnapshotResponse remoteResponse;
                  try {
                    remoteResponse = Futures.getDone(fromRemote);
                  } catch (ExecutionException e) {
                    // The local answer stands alone when the service is unreachable or older.
                    return localResponse;
                  }
                  if (!localResponse.hasSnapshot()) {
                    return remoteResponse;
                  }
                  if (!remoteResponse.hasSnapshot()) {
                    return localResponse;
                  }
                  return localResponse.getSnapshot().getMasterOrdinal()
                          >= remoteResponse.getSnapshot().getMasterOrdinal()
                      ? localResponse
                      : remoteResponse;
                },
                directExecutor());
      }
    };
  }
}
