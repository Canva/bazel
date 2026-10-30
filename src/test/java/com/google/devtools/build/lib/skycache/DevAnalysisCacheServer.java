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

import build.aspect.skycache.v1.AnalysisCacheGrpc;
import build.aspect.skycache.v1.Entry;
import build.aspect.skycache.v1.GetRequest;
import build.aspect.skycache.v1.GetResponse;
import build.aspect.skycache.v1.PutRequest;
import build.aspect.skycache.v1.PutResponse;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An in-memory analysis cache service for trying a patched Bazel by hand.
 *
 * <p>Speaks the same protocol as the real service, with no authentication and no persistence:
 * entries last as long as the process. Writes are first-wins, as the real service's are. Also
 * serves the snapshot index uploads are recorded in. Prints its counters each time they change, at
 * most once a second.
 *
 * <p>Usage: {@code DevAnalysisCacheServer <port>}, then point Bazel at {@code
 * --experimental_remote_analysis_cache=grpc://localhost:<port>}.
 */
public final class DevAnalysisCacheServer extends AnalysisCacheGrpc.AnalysisCacheImplBase {

  private final ConcurrentHashMap<ByteString, ByteString> entries = new ConcurrentHashMap<>();
  private final AtomicLong keysRequested = new AtomicLong();
  private final AtomicLong keysHit = new AtomicLong();
  private final AtomicLong entriesOffered = new AtomicLong();
  private final DevSnapshotIndex snapshots = new DevSnapshotIndex();

  @Override
  public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
    var response = GetResponse.newBuilder();
    for (ByteString key : request.getKeysList()) {
      keysRequested.incrementAndGet();
      ByteString value = entries.get(key);
      if (value != null) {
        keysHit.incrementAndGet();
        response.addEntries(Entry.newBuilder().setKey(key).setValue(value));
      }
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
    long stored = 0;
    for (Entry entry : request.getEntriesList()) {
      entriesOffered.incrementAndGet();
      if (entries.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
        stored++;
      }
    }
    responseObserver.onNext(PutResponse.newBuilder().setStored(stored).build());
    responseObserver.onCompleted();
  }

  boolean has(ByteString key) {
    return entries.containsKey(key);
  }

  private String counters() {
    return String.format(
        "entries=%d offered=%d requested=%d hit=%d snapshots=%d",
        entries.size(),
        entriesOffered.get(),
        keysRequested.get(),
        keysHit.get(),
        snapshots.size());
  }

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    var service = new DevAnalysisCacheServer();
    Server server =
        NettyServerBuilder.forPort(port)
            .addService(service)
            .addService(service.snapshots)
            .maxInboundMessageSize(256 * 1024 * 1024)
            .build()
            .start();
    System.out.println("listening on " + port);
    String last = "";
    while (!server.isTerminated()) {
      Thread.sleep(1000);
      String now = service.counters();
      if (!now.equals(last)) {
        System.out.println(now);
        last = now;
      }
    }
  }
}
