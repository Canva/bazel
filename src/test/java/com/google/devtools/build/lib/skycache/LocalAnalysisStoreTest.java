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

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.Snapshot;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.lib.skyframe.serialization.InMemoryFingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.StringKey;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutionException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class LocalAnalysisStoreTest {

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private Path dir() throws Exception {
    return tmp.getRoot().toPath().resolve("store");
  }

  private LocalAnalysisStore open() throws Exception {
    return LocalAnalysisStore.open(dir(), LocalAnalysisStore.DEFAULT_MAX_BYTES);
  }

  private static KeyBytesProvider key(String name) {
    return new StringKey(name);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  @Test
  public void sealedWrites_areReadByTheNextCommand() throws Exception {
    try (var writer = open()) {
      assertThat(writer.put(key("a"), bytes("1")).get()).isTrue();
      assertThat(writer.put(key("b"), bytes("22")).get()).isTrue();
    }

    try (var reader = open()) {
      assertThat(reader.get(key("a")).get()).isEqualTo(bytes("1"));
      assertThat(reader.get(key("b")).get()).isEqualTo(bytes("22"));
      var e = assertThrows(ExecutionException.class, () -> reader.get(key("c")).get());
      assertThat(e).hasCauseThat().isInstanceOf(MissingFingerprintValueException.class);
    }
  }

  @Test
  public void unsealedWrites_areNotYetVisible() throws Exception {
    var writer = open();
    writer.put(key("a"), bytes("1")).get();

    try (var reader = open()) {
      assertThat(reader.read(bytes("a"))).isNull();
    }
    writer.close();
    try (var reader = open()) {
      assertThat(reader.read(bytes("a"))).isEqualTo(bytes("1"));
    }
  }

  @Test
  public void repeatedPut_isNotNovel() throws Exception {
    try (var writer = open()) {
      assertThat(writer.put(key("a"), bytes("1")).get()).isTrue();
      assertThat(writer.put(key("a"), bytes("1")).get()).isFalse();
    }
    try (var writer = open()) {
      assertThat(writer.put(key("a"), bytes("1")).get()).isFalse();
      assertThat(writer.getStats().entriesWritten()).isEqualTo(0);
    }
  }

  @Test
  public void concurrentWriters_bothLand() throws Exception {
    var first = open();
    var second = open();
    first.put(key("a"), bytes("1")).get();
    second.put(key("b"), bytes("2")).get();
    // Both wrote the same entry too, which is indexed once.
    first.put(key("c"), bytes("3")).get();
    second.put(key("c"), bytes("3")).get();
    second.close();
    first.close();

    try (var reader = open()) {
      assertThat(reader.read(bytes("a"))).isEqualTo(bytes("1"));
      assertThat(reader.read(bytes("b"))).isEqualTo(bytes("2"));
      assertThat(reader.read(bytes("c"))).isEqualTo(bytes("3"));
    }
  }

  @Test
  public void manyEntries_growTheTable() throws Exception {
    int count = 100_000;
    try (var writer = open()) {
      for (int i = 0; i < count; i++) {
        writer.put(key("k" + i), bytes("v" + i)).get();
      }
    }
    try (var reader = open()) {
      for (int i = 0; i < count; i += 997) {
        assertThat(reader.read(bytes("k" + i))).isEqualTo(bytes("v" + i));
      }
    }
    assertThat(Files.size(dir().resolve("v1/table"))).isGreaterThan(16L * count);
  }

  @Test
  public void corruptRecord_isAMiss() throws Exception {
    try (var writer = open()) {
      writer.put(key("a"), bytes("value")).get();
    }
    Path segment = dir().resolve("v1/segments/1.seg");
    try (var channel = FileChannel.open(segment, StandardOpenOption.WRITE)) {
      // The last value byte, just before the checksum.
      channel.write(ByteBuffer.wrap(new byte[] {'X'}), channel.size() - 5);
    }

    try (var reader = open()) {
      assertThat(reader.read(bytes("a"))).isNull();
    }
  }

  @Test
  public void overTheLimit_oldestSegmentsAreCollected() throws Exception {
    byte[] value = new byte[1000];
    for (int command = 0; command < 10; command++) {
      try (var writer = LocalAnalysisStore.open(dir(), /* maxBytes= */ 5000)) {
        writer.put(key("c" + command), value).get();
      }
    }

    try (var reader = open()) {
      assertThat(reader.read(bytes("c0"))).isNull();
      assertThat(reader.read(bytes("c9"))).isEqualTo(value);
    }
    long total = 0;
    try (var segments = Files.list(dir().resolve("v1/segments"))) {
      for (Path segment : segments.toList()) {
        total += Files.size(segment);
      }
    }
    assertThat(total).isAtMost(5000);
  }

  @Test
  public void snapshots_newestAtOrBeforeTheOrdinal() throws Exception {
    try (var store = open()) {
      for (int ordinal : new int[] {10, 20, 30}) {
        store
            .record(
                RecordSnapshotRequest.newBuilder()
                    .setSnapshot(
                        Snapshot.newBuilder()
                            .setBazelInstallMd5("md5")
                            .setConfigurationHash("config")
                            .setMasterOrdinal(ordinal)
                            .setCommit("c" + ordinal))
                    .build())
            .get();
      }
    }

    try (var store = open()) {
      var request =
          LookupSnapshotRequest.newBuilder()
              .setBazelInstallMd5("md5")
              .setConfigurationHash("config");
      assertThat(store.lookup(request.setMaxMasterOrdinal(25).build()).get().getSnapshot().getCommit())
          .isEqualTo("c20");
      assertThat(store.lookup(request.setMaxMasterOrdinal(5).build()).get().hasSnapshot()).isFalse();
      assertThat(
              store
                  .lookup(request.setConfigurationHash("other").setMaxMasterOrdinal(30).build())
                  .get()
                  .hasSnapshot())
          .isFalse();
    }
  }

  @Test
  public void tiered_keepsWhatTheServiceAnswered() throws Exception {
    FingerprintValueStore remote = new InMemoryFingerprintValueStore();
    remote.put(key("a"), bytes("1")).get();
    try (var local = open()) {
      var tiered = new TieredFingerprintValueStore(local, remote);
      assertThat(tiered.get(key("a")).get()).isEqualTo(bytes("1"));
      tiered.put(key("b"), bytes("2")).get();
      var e = assertThrows(ExecutionException.class, () -> tiered.get(key("c")).get());
      assertThat(e).hasCauseThat().isInstanceOf(MissingFingerprintValueException.class);
    }

    try (var local = open()) {
      assertThat(local.read(bytes("a"))).isEqualTo(bytes("1"));
      assertThat(local.read(bytes("b"))).isEqualTo(bytes("2"));
    }
    assertThrows(ExecutionException.class, () -> remote.get(key("b")).get());
  }
}
