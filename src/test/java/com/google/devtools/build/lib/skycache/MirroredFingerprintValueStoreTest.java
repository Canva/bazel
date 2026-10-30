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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertThrows;

import build.aspect.skycache.v1.AnalysisCacheGrpc;
import build.aspect.skycache.v1.GetRequest;
import build.aspect.skycache.v1.PutRequest;
import build.aspect.skycache.v1.PutResponse;
import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.Snapshot;
import com.canva.skycache.v1.SnapshotIndexGrpc;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.InMemoryFingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.StringKey;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class MirroredFingerprintValueStoreTest {

  private final List<Server> servers = new ArrayList<>();
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @After
  public void shutDown() {
    channels.forEach(ManagedChannel::shutdownNow);
    servers.forEach(Server::shutdownNow);
    scheduler.shutdownNow();
  }

  /** Refuses every write. */
  private static final class BrokenCache extends AnalysisCacheGrpc.AnalysisCacheImplBase {
    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
      responseObserver.onError(Status.UNAVAILABLE.withDescription("region down").asException());
    }

    @Override
    public void get(
        GetRequest request, StreamObserver<build.aspect.skycache.v1.GetResponse> observer) {
      observer.onError(Status.UNAVAILABLE.asException());
    }
  }

  private ManagedChannel serve(BindableService... services) throws Exception {
    String name = InProcessServerBuilder.generateName();
    var builder = InProcessServerBuilder.forName(name).directExecutor();
    for (BindableService service : services) {
      builder.addService(service);
    }
    servers.add(builder.build().start());
    ManagedChannel channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    channels.add(channel);
    return channel;
  }

  private MirroredFingerprintValueStore.Mirror mirror(String target, ManagedChannel channel) {
    return new MirroredFingerprintValueStore.Mirror(
        target,
        new BatchingPutQueue(
            AnalysisCacheGrpc.newFutureStub(channel),
            /* maxBatchSize= */ 2,
            /* lingerMillis= */ 1,
            /* deadlineMillis= */ 10_000,
            scheduler),
        SnapshotIndex.of(SnapshotIndexGrpc.newFutureStub(channel)));
  }

  private static RecordSnapshotRequest snapshot() {
    return RecordSnapshotRequest.newBuilder()
        .setSnapshot(
            Snapshot.newBuilder()
                .setBazelInstallMd5("md5")
                .setConfigurationHash("config")
                .setMasterOrdinal(7)
                .setCommit("abc"))
        .build();
  }

  private static LookupSnapshotRequest lookup() {
    return LookupSnapshotRequest.newBuilder()
        .setBazelInstallMd5("md5")
        .setConfigurationHash("config")
        .setMaxMasterOrdinal(7)
        .build();
  }

  @Test
  public void writesReachEveryMirror_aBrokenOneOnlyLosesItsSnapshot() throws Exception {
    FingerprintValueStore primary = new InMemoryFingerprintValueStore();
    var primaryIndex = new DevSnapshotIndex();
    var healthyCache = new DevAnalysisCacheServer();
    var healthyIndex = new DevSnapshotIndex();
    var brokenIndex = new DevSnapshotIndex();
    var healthy = mirror("healthy", serve(healthyCache, healthyIndex));
    var broken = mirror("broken", serve(new BrokenCache(), brokenIndex));
    var store = new MirroredFingerprintValueStore(primary, ImmutableList.of(healthy, broken));

    for (int i = 0; i < 5; i++) {
      // Completes, broken mirror and all: the build's copy is fine.
      var unused = store.put(new StringKey("k" + i), ("v" + i).getBytes(UTF_8)).get();
    }

    assertThat(primary.get(new StringKey("k3")).get()).isEqualTo("v3".getBytes(UTF_8));
    assertThat(store.get(new StringKey("k3")).get()).isEqualTo("v3".getBytes(UTF_8));
    assertThat(healthyCache.has(ByteString.copyFromUtf8("k3"))).isTrue();
    assertThat(healthy.failure()).isNull();
    assertThat(broken.failure()).contains("5 writes failed");

    var index =
        SnapshotIndex.mirrored(
            SnapshotIndex.of(SnapshotIndexGrpc.newFutureStub(serve(primaryIndex))),
            ImmutableList.of(healthy, broken));
    var thrown = assertThrows(ExecutionException.class, () -> index.record(snapshot()).get());
    assertThat(thrown).hasCauseThat().hasMessageThat().contains("not in mirrors broken");

    assertThat(index.lookup(lookup()).get().getSnapshot().getCommit()).isEqualTo("abc");
    assertThat(healthy.index().lookup(lookup()).get().hasSnapshot()).isTrue();
    assertThat(brokenIndex.size()).isEqualTo(0);
  }
}
