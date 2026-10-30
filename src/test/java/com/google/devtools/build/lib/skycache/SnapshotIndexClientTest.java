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
import static org.junit.Assert.assertThrows;

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.LookupSnapshotResponse;
import com.canva.skycache.v1.SnapshotIndexGrpc;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.util.Bucket;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LookupTopLevelTargetsResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class SnapshotIndexClientTest {

  private static final String MD5 = "0123456789abcdef0123456789abcdef";
  private static final String BAZEL_VERSION = "10.0.0-rc2-" + MD5;
  private static final String CONFIG = "config-a";
  private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC);

  private final DevSnapshotIndex index = new DevSnapshotIndex();
  private Server server;
  private ManagedChannel channel;

  @After
  public void shutDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  private SnapshotIndexClient client(String commit, int ordinal, boolean withIndex)
      throws IOException {
    if (channel == null) {
      String name = InProcessServerBuilder.generateName();
      var builder = InProcessServerBuilder.forName(name).directExecutor();
      if (withIndex) {
        builder.addService(index);
      }
      server = builder.build().start();
      channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    }
    return new SnapshotIndexClient(
        SnapshotIndexGrpc.newFutureStub(channel),
        new GitVersions.Base(commit, ordinal),
        SnapshotIndexClientTest::stats,
        CLOCK);
  }

  private void upload(String commit, int ordinal, String config) throws Exception {
    assertThat(
            client(commit, ordinal, true)
                .addTopLevelTargets(
                    "invocation",
                    ordinal,
                    config,
                    /* useFakeStampData= */ true,
                    BAZEL_VERSION,
                    ImmutableList.of("//a:a"),
                    ImmutableList.of()))
        .isTrue();
  }

  private LookupTopLevelTargetsResult lookup(int ordinal, String config, boolean fakeStamp)
      throws Exception {
    return client("reader", ordinal, true).lookup(ordinal, config, fakeStamp, BAZEL_VERSION);
  }

  private static TopLevelTargetsMatchStatus status(LookupTopLevelTargetsResult result) {
    return TopLevelTargetsMatchStatus.forNumber(result.status());
  }

  @Test
  public void record_storesTheUploadUnderTheKeysInstallAndConfiguration() throws Exception {
    upload("abc123", 40, CONFIG);

    var response =
        lookupRaw(
            LookupSnapshotRequest.newBuilder()
                .setBazelInstallMd5(MD5)
                .setConfigurationHash(CONFIG)
                .setMaxMasterOrdinal(40)
                .build());
    assertThat(response.hasSnapshot()).isTrue();
    var snapshot = response.getSnapshot();
    assertThat(snapshot.getCommit()).isEqualTo("abc123");
    assertThat(snapshot.getMasterOrdinal()).isEqualTo(40);
    assertThat(snapshot.getBazelVersion()).isEqualTo(BAZEL_VERSION);
    assertThat(snapshot.getTargetsList()).containsExactly("//a:a");
    assertThat(snapshot.getRecordedUnixMillis()).isEqualTo(1234);
    assertThat(snapshot.getEntries()).isEqualTo(7);
    assertThat(snapshot.getBytes()).isEqualTo(30);
  }

  @Test
  public void lookup_atThePrimedBase_matches() throws Exception {
    upload("abc123", 40, CONFIG);

    var result = lookup(40, CONFIG, true);

    assertThat(status(result)).isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH);
    assertThat(result.statusMessage()).contains("abc123");
  }

  @Test
  public void lookup_afterThePrimedBase_namesTheCommitToUse() throws Exception {
    upload("abc123", 40, CONFIG);
    upload("def456", 45, CONFIG);

    var result = lookup(50, CONFIG, true);

    assertThat(status(result)).isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_DIFFERENT_CL);
    assertThat(result.statusMessage()).contains("--canva_skycache_base=def456");
    assertThat(result.statusMessage()).contains("5 mainline commits earlier");
  }

  @Test
  public void lookup_beforeAnyUpload_doesNotMatch() throws Exception {
    upload("abc123", 40, CONFIG);

    assertThat(status(lookup(39, CONFIG, true)))
        .isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_NO_MATCH);
  }

  @Test
  public void lookup_withAnotherConfiguration_doesNotMatch() throws Exception {
    upload("abc123", 40, CONFIG);

    assertThat(status(lookup(40, "config-b", true)))
        .isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_NO_MATCH);
  }

  @Test
  public void lookup_stamped_doesNotMatchAnUnstampedUpload() throws Exception {
    upload("abc123", 40, CONFIG);

    assertThat(status(lookup(40, CONFIG, /* fakeStamp= */ false)))
        .isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_NO_MATCH);
  }

  @Test
  public void lookup_againstAServiceWithoutTheIndex_proceeds() throws Exception {
    var result = client("reader", 40, false).lookup(40, CONFIG, true, BAZEL_VERSION);

    assertThat(status(result)).isEqualTo(TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH);
    assertThat(result.statusMessage()).contains("no snapshot index");
  }

  @Test
  public void record_atAnotherVersionThanTheBase_fails() throws Exception {
    var client = client("abc123", 40, true);

    var thrown =
        assertThrows(
            IOException.class,
            () ->
                client.addTopLevelTargets(
                    "invocation",
                    41,
                    CONFIG,
                    true,
                    BAZEL_VERSION,
                    ImmutableList.of(),
                    ImmutableList.of()));
    assertThat(thrown).hasMessageThat().contains("versioned at 41");
    assertThat(index.size()).isEqualTo(0);
  }

  @Test
  public void record_rejectedByTheService_fails() throws Exception {
    var client = client("", 40, true); // The service rejects a snapshot without a commit.

    assertThrows(
        IOException.class,
        () ->
            client.addTopLevelTargets(
                "invocation",
                40,
                CONFIG,
                true,
                BAZEL_VERSION,
                ImmutableList.of(),
                ImmutableList.of()));
  }

  @Test
  public void installMd5_isTheSuffixOfTheVersionString() {
    assertThat(SnapshotIndexClient.installMd5("release 10.0.0-rc2-" + MD5)).isEqualTo(MD5);
    assertThat(SnapshotIndexClient.installMd5("development version-" + MD5)).isEqualTo(MD5);
  }

  private LookupSnapshotResponse lookupRaw(LookupSnapshotRequest request) {
    return SnapshotIndexGrpc.newBlockingStub(channel).lookup(request);
  }

  private static FingerprintValueStore.Stats stats() {
    return new FingerprintValueStore.Stats() {
      @Override
      public long valueBytesReceived() {
        return 0;
      }

      @Override
      public long valueBytesSent() {
        return 20;
      }

      @Override
      public long keyBytesSent() {
        return 10;
      }

      @Override
      public long entriesWritten() {
        return 7;
      }

      @Override
      public long entriesFound() {
        return 0;
      }

      @Override
      public long entriesNotFound() {
        return 0;
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
