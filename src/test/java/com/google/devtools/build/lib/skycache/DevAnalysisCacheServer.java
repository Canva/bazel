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
import build.aspect.skycache.v1.FetchRequest;
import build.aspect.skycache.v1.FetchResponse;
import build.aspect.skycache.v1.GetRequest;
import build.aspect.skycache.v1.GetResponse;
import build.aspect.skycache.v1.PutRequest;
import build.aspect.skycache.v1.PutResponse;
import com.google.devtools.build.lib.skyframe.serialization.References;
import com.google.protobuf.ByteString;
import io.grpc.Server;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

  /** By key, the hashes of the keys its entry was written as referencing. */
  private final ConcurrentHashMap<ByteString, List<Long>> references = new ConcurrentHashMap<>();

  /** By the hash a reference is, the key it refers to: the first written with that hash. */
  private final ConcurrentHashMap<Long, ByteString> keysByHash = new ConcurrentHashMap<>();

  private final AtomicLong entriesPushed = new AtomicLong();
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
        byte[] key = entry.getKey().toByteArray();
        keysByHash.putIfAbsent(References.hash(key, 0, key.length), entry.getKey());
        if (!entry.getReferences().isEmpty()) {
          references.put(entry.getKey(), unpack(entry.getReferences()));
        }
      }
    }
    responseObserver.onNext(PutResponse.newBuilder().setStored(stored).build());
    responseObserver.onCompleted();
  }

  /** Splits packed references: key hashes of eight bytes, least significant first. */
  private static List<Long> unpack(ByteString packed) {
    var hashes = new ArrayList<Long>();
    for (int i = 0; i + References.BYTES <= packed.size(); i += References.BYTES) {
      long hash = 0;
      for (int b = 0; b < References.BYTES; b++) {
        hash |= (packed.byteAt(i + b) & 0xffL) << (8 * b);
      }
      hashes.add(hash);
    }
    return hashes;
  }

  /**
   * Answers each request with the entries of its keys and, if asked to expand, everything
   * reachable from them, sending no entry twice on a stream unless forced to.
   */
  @Override
  public StreamObserver<FetchRequest> fetch(StreamObserver<FetchResponse> responseObserver) {
    var sent = new HashSet<ByteString>();
    return new StreamObserver<>() {
      @Override
      public synchronized void onNext(FetchRequest request) {
        var response = FetchResponse.newBuilder();
        var frontier = new ArrayDeque<ByteString>();
        for (ByteString key : request.getKeysList()) {
          keysRequested.incrementAndGet();
          if (!entries.containsKey(key)) {
            response.addMissing(key);
          } else if (!sent.add(key) && !request.getForce()) {
            response.addAlreadySent(key);
          } else {
            keysHit.incrementAndGet();
            frontier.add(key);
          }
        }
        int requested = frontier.size();
        int answered = 0;
        while (!frontier.isEmpty()) {
          ByteString key = frontier.remove();
          response.addEntries(Entry.newBuilder().setKey(key).setValue(entries.get(key)));
          answered++;
          if (!request.getExpand()) {
            continue;
          }
          for (Long reference : references.getOrDefault(key, List.of())) {
            ByteString child = keysByHash.get(reference);
            if (child != null && sent.add(child)) {
              frontier.add(child);
            }
          }
        }
        entriesPushed.addAndGet(answered - requested);
        responseObserver.onNext(response.build());
      }

      @Override
      public void onError(Throwable t) {}

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }

  /** Entries sent because a requested entry referenced them. */
  long entriesPushed() {
    return entriesPushed.get();
  }

  /** Keys asked for, over Get and Fetch alike. */
  long keysRequested() {
    return keysRequested.get();
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
