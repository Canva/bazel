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

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.LookupSnapshotResponse;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.RecordSnapshotResponse;
import com.canva.skycache.v1.Snapshot;
import com.canva.skycache.v1.SnapshotIndexGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * An in-memory snapshot index, answering as the real service's does: the newest snapshot for an
 * install and configuration at or before the ordinal asked for, with a later record at the same
 * ordinal replacing the earlier one.
 */
final class DevSnapshotIndex extends SnapshotIndexGrpc.SnapshotIndexImplBase {

  private final Map<String, NavigableMap<Long, Snapshot>> snapshots = new ConcurrentHashMap<>();

  @Override
  public void record(
      RecordSnapshotRequest request, StreamObserver<RecordSnapshotResponse> responseObserver) {
    Snapshot snapshot = request.getSnapshot();
    if (snapshot.getBazelInstallMd5().isEmpty()
        || snapshot.getConfigurationHash().isEmpty()
        || snapshot.getMasterOrdinal() <= 0
        || snapshot.getCommit().isEmpty()) {
      responseObserver.onError(
          Status.INVALID_ARGUMENT.withDescription("incomplete snapshot").asRuntimeException());
      return;
    }
    snapshots
        .computeIfAbsent(
            key(snapshot.getBazelInstallMd5(), snapshot.getConfigurationHash()),
            unused -> new ConcurrentSkipListMap<>())
        .put(snapshot.getMasterOrdinal(), snapshot);
    responseObserver.onNext(RecordSnapshotResponse.getDefaultInstance());
    responseObserver.onCompleted();
  }

  @Override
  public void lookup(
      LookupSnapshotRequest request, StreamObserver<LookupSnapshotResponse> responseObserver) {
    var response = LookupSnapshotResponse.newBuilder();
    NavigableMap<Long, Snapshot> forKey =
        snapshots.get(key(request.getBazelInstallMd5(), request.getConfigurationHash()));
    if (forKey != null) {
      Map.Entry<Long, Snapshot> newest = forKey.floorEntry(request.getMaxMasterOrdinal());
      if (newest != null) {
        response.setSnapshot(newest.getValue());
      }
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  int size() {
    return snapshots.values().stream().mapToInt(Map::size).sum();
  }

  private static String key(String installMd5, String configurationHash) {
    return installMd5 + "/" + configurationHash;
  }
}
