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
import static java.util.concurrent.TimeUnit.SECONDS;

import build.aspect.skycache.v1.AnalysisCacheGrpc;
import build.aspect.skycache.v1.Entry;
import build.aspect.skycache.v1.FetchRequest;
import build.aspect.skycache.v1.FetchResponse;
import build.aspect.skycache.v1.GetRequest;
import build.aspect.skycache.v1.GetResponse;
import build.aspect.skycache.v1.PutRequest;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.References;
import com.google.protobuf.ByteString;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class StreamingFetchQueueTest {

  private final List<Server> servers = new ArrayList<>();
  private final List<ManagedChannel> channels = new ArrayList<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  @After
  public void shutDown() {
    channels.forEach(ManagedChannel::shutdownNow);
    servers.forEach(Server::shutdownNow);
    scheduler.shutdownNow();
  }

  private ManagedChannel serve(BindableService service) throws Exception {
    String name = InProcessServerBuilder.generateName();
    servers.add(InProcessServerBuilder.forName(name).addService(service).build().start());
    ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
    channels.add(channel);
    return channel;
  }

  private StreamingFetchQueue queue(ManagedChannel channel) {
    BatchingGetQueue batches =
        new BatchingGetQueue(
            AnalysisCacheGrpc.newFutureStub(channel),
            /* maxBatchSize= */ 1024,
            /* lingerMillis= */ 1,
            /* maxInFlight= */ 0,
            /* deadlineMillis= */ 10_000,
            scheduler);
    return new StreamingFetchQueue(
        AnalysisCacheGrpc.newStub(channel),
        batches,
        /* maxBatchSize= */ 1024,
        scheduler,
        MoreExecutors.directExecutor());
  }

  private static byte[] key(String name) {
    return name.getBytes(UTF_8);
  }

  /** A 16-byte key, the shape nearly every real one has. */
  private static byte[] fingerprint(String name) {
    return PackedFingerprint.getFingerprintForTesting(name).toBytes();
  }

  private static Entry entry(byte[] key, String value, byte[]... references) {
    var builder =
        Entry.newBuilder().setKey(ByteString.copyFrom(key)).setValue(ByteString.copyFromUtf8(value));
    if (references.length > 0) {
      var packed = new References();
      for (byte[] reference : references) {
        packed.add(reference);
      }
      builder.setReferences(ByteString.copyFrom(packed.pack()));
    }
    return builder.build();
  }

  private static void put(ManagedChannel channel, Entry... entries) {
    var unused =
        AnalysisCacheGrpc.newBlockingStub(channel)
            .put(PutRequest.newBuilder().addAllEntries(List.of(entries)).build());
  }

  private static String read(ListenableFuture<byte[]> future) throws Exception {
    byte[] value = future.get(10, SECONDS);
    return value == null ? null : new String(value, UTF_8);
  }

  @Test
  public void nestedReads_areServedFromWhatTheServicePushed() throws Exception {
    var service = new DevAnalysisCacheServer();
    ManagedChannel channel = serve(service);
    byte[] root = fingerprint("root");
    byte[] middle = fingerprint("middle");
    byte[] leaf = key("a string key, as invalidation data has");
    put(
        channel,
        entry(leaf, "L"),
        entry(middle, "M", leaf),
        entry(root, "R", middle));
    StreamingFetchQueue queue = queue(channel);

    assertThat(read(queue.enqueue(root))).isEqualTo("R");
    // The closure arrived with the root, in the same response, before these were asked for.
    assertThat(read(queue.enqueue(middle))).isEqualTo("M");
    assertThat(read(queue.enqueue(leaf))).isEqualTo("L");

    assertThat(service.keysRequested()).isEqualTo(1);
    assertThat(service.entriesPushed()).isEqualTo(2);
    assertThat(queue.keysRequested()).isEqualTo(1);
    assertThat(queue.entriesReceived()).isEqualTo(3);
    assertThat(queue.servedFromBuffer()).isEqualTo(2);
  }

  @Test
  public void unexpandedRead_bringsTheEntryAlone_andItsReferencesWhenTheyAreRead()
      throws Exception {
    var service = new DevAnalysisCacheServer();
    ManagedChannel channel = serve(service);
    byte[] root = fingerprint("root");
    byte[] invalidation = fingerprint("invalidation");
    byte[] file = key("a file the root depends on");
    byte[] value = fingerprint("value");
    byte[] nested = fingerprint("nested");
    put(
        channel,
        entry(file, "F"),
        entry(invalidation, "I", file),
        entry(nested, "N"),
        entry(value, "V", nested),
        entry(root, "R", invalidation, value));
    StreamingFetchQueue queue = queue(channel);

    // A reader that has yet to decide whether it will use the root.
    assertThat(read(queue.enqueueUnexpanded(root))).isEqualTo("R");
    assertThat(queue.entriesReceived()).isEqualTo(1);

    // It reads the invalidation data to decide, and that comes whole.
    assertThat(read(queue.enqueue(invalidation))).isEqualTo("I");
    assertThat(read(queue.enqueue(file))).isEqualTo("F");
    assertThat(queue.entriesReceived()).isEqualTo(3);
    assertThat(service.keysRequested()).isEqualTo(2);

    // Had it rejected the root it would stop here, with nothing of the value sent. It does not.
    assertThat(read(queue.enqueue(value))).isEqualTo("V");
    assertThat(read(queue.enqueue(nested))).isEqualTo("N");
    assertThat(queue.entriesReceived()).isEqualTo(5);
    assertThat(service.keysRequested()).isEqualTo(3);
  }

  @Test
  public void absentKey_isAMiss() throws Exception {
    ManagedChannel channel = serve(new DevAnalysisCacheServer());
    StreamingFetchQueue queue = queue(channel);

    assertThat(read(queue.enqueue(fingerprint("nothing")))).isNull();
  }

  @Test
  public void unrecordedReference_costsARequestOfItsOwn() throws Exception {
    var service = new DevAnalysisCacheServer();
    ManagedChannel channel = serve(service);
    byte[] root = fingerprint("root");
    byte[] child = fingerprint("child");
    put(channel, entry(child, "C"), entry(root, "R")); // The writer recorded no reference.
    StreamingFetchQueue queue = queue(channel);

    assertThat(read(queue.enqueue(root))).isEqualTo("R");
    assertThat(read(queue.enqueue(child))).isEqualTo("C");

    assertThat(service.keysRequested()).isEqualTo(2);
    assertThat(queue.servedFromBuffer()).isEqualTo(0);
  }

  @Test
  public void entryReadTwice_isAskedForAgain() throws Exception {
    var service = new DevAnalysisCacheServer();
    ManagedChannel channel = serve(service);
    byte[] root = fingerprint("root");
    byte[] child = fingerprint("child");
    put(channel, entry(child, "C"), entry(root, "R", child));
    StreamingFetchQueue queue = queue(channel);

    assertThat(read(queue.enqueue(root))).isEqualTo("R");
    assertThat(read(queue.enqueue(child))).isEqualTo("C");
    // Bazel dropped the deserialized value and wants the bytes again. The buffer gave them up on
    // the first read, and the service believes it has sent them.
    assertThat(read(queue.enqueue(child))).isEqualTo("C");

    assertThat(queue.forced()).isEqualTo(1);
  }

  @Test
  public void concurrentReadsOfOneKey_shareOneRequest() throws Exception {
    var release = new CountDownLatch(1);
    var requests = new AtomicInteger();
    var service =
        new AnalysisCacheGrpc.AnalysisCacheImplBase() {
          @Override
          public StreamObserver<FetchRequest> fetch(StreamObserver<FetchResponse> responses) {
            return new StreamObserver<>() {
              @Override
              public void onNext(FetchRequest request) {
                requests.addAndGet(request.getKeysCount());
                try {
                  release.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                responses.onNext(
                    FetchResponse.newBuilder()
                        .addEntries(
                            Entry.newBuilder()
                                .setKey(request.getKeys(0))
                                .setValue(ByteString.copyFromUtf8("V")))
                        .build());
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responses.onCompleted();
              }
            };
          }
        };
    StreamingFetchQueue queue = queue(serve(service));
    byte[] key = fingerprint("key");

    ListenableFuture<byte[]> first = queue.enqueue(key);
    ListenableFuture<byte[]> second = queue.enqueue(key);
    release.countDown();

    assertThat(read(first)).isEqualTo("V");
    assertThat(read(second)).isEqualTo("V");
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  public void serviceWithoutFetch_isReadInBatches() throws Exception {
    var gets = new AtomicInteger();
    var service =
        new AnalysisCacheGrpc.AnalysisCacheImplBase() {
          @Override
          public void get(GetRequest request, StreamObserver<GetResponse> observer) {
            gets.incrementAndGet();
            var response = GetResponse.newBuilder();
            for (ByteString key : request.getKeysList()) {
              if (!key.equals(ByteString.copyFrom(fingerprint("absent")))) {
                response.addEntries(
                    Entry.newBuilder().setKey(key).setValue(ByteString.copyFromUtf8("V")));
              }
            }
            observer.onNext(response.build());
            observer.onCompleted();
          }
        };
    StreamingFetchQueue queue = queue(serve(service));

    // Asked for while the stream is still being refused, and after.
    ListenableFuture<byte[]> first = queue.enqueue(fingerprint("first"));
    ListenableFuture<byte[]> absent = queue.enqueue(fingerprint("absent"));
    assertThat(read(first)).isEqualTo("V");
    assertThat(read(absent)).isNull();
    assertThat(read(queue.enqueue(fingerprint("later")))).isEqualTo("V");

    assertThat(gets.get()).isAtLeast(1);
  }

  @Test
  public void brokenStream_failsReadsAndIsReopened() throws Exception {
    var streams = new AtomicInteger();
    var service =
        new AnalysisCacheGrpc.AnalysisCacheImplBase() {
          @Override
          public StreamObserver<FetchRequest> fetch(StreamObserver<FetchResponse> responses) {
            boolean broken = streams.incrementAndGet() == 1;
            return new StreamObserver<>() {
              @Override
              public void onNext(FetchRequest request) {
                if (broken) {
                  responses.onError(io.grpc.Status.UNAVAILABLE.asException());
                  return;
                }
                responses.onNext(
                    FetchResponse.newBuilder()
                        .addEntries(
                            Entry.newBuilder()
                                .setKey(request.getKeys(0))
                                .setValue(ByteString.copyFromUtf8("V")))
                        .build());
              }

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {
                responses.onCompleted();
              }
            };
          }
        };
    StreamingFetchQueue queue = queue(serve(service));

    ListenableFuture<byte[]> failed = queue.enqueue(fingerprint("first"));
    try {
      failed.get(10, SECONDS);
      throw new AssertionError("expected the read on the broken stream to fail");
    } catch (java.util.concurrent.ExecutionException expected) {
      assertThat(io.grpc.Status.fromThrowable(expected.getCause()).getCode())
          .isEqualTo(io.grpc.Status.Code.UNAVAILABLE);
    }
    // The failure is of that stream, not of the queue.
    assertThat(read(queue.enqueue(fingerprint("second")))).isEqualTo("V");
    assertThat(streams.get()).isEqualTo(2);
  }
}
