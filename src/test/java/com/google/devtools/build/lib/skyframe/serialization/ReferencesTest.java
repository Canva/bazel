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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class ReferencesTest {

  private static long hash(String key) {
    byte[] bytes = key.getBytes(UTF_8);
    return References.hash(bytes, 0, bytes.length);
  }

  @Test
  public void hash_isFnv1a64() {
    // The published test vectors. The analysis cache service computes the same hash to find the
    // entry a reference names, so this is part of the wire format.
    assertThat(hash("")).isEqualTo(0xcbf29ce484222325L);
    assertThat(hash("a")).isEqualTo(0xaf63dc4c8601ec8cL);
    assertThat(hash("foobar")).isEqualTo(0x85944171f73967e8L);
  }

  @Test
  public void pack_isEachHashLeastSignificantByteFirst() {
    PackedFingerprint fingerprint = PackedFingerprint.getFingerprintForTesting("some value");
    byte[] fingerprintBytes = fingerprint.toBytes();
    byte[] surrounding = new byte[40];
    System.arraycopy(fingerprintBytes, 0, surrounding, 7, fingerprintBytes.length);

    byte[] packed =
        new References()
            .add("foobar".getBytes(UTF_8))
            .add(fingerprint)
            .addFingerprintAt(surrounding, 7)
            .add(new StringKey("a"))
            .pack();

    ByteBuffer buffer = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(packed).hasLength(4 * References.BYTES);
    assertThat(buffer.getLong()).isEqualTo(0x85944171f73967e8L);
    long fingerprintHash = References.hash(fingerprintBytes, 0, fingerprintBytes.length);
    assertThat(buffer.getLong()).isEqualTo(fingerprintHash);
    assertThat(buffer.getLong()).isEqualTo(fingerprintHash);
    assertThat(buffer.getLong()).isEqualTo(0xaf63dc4c8601ec8cL);
  }

  @Test
  public void pack_ofNothing_isNull() {
    assertThat(new References().pack()).isNull();
    assertThat(new References().addAll(null).isEmpty()).isTrue();
  }

  @Test
  public void addAll_appendsPackedReferences() {
    byte[] first = new References().add("a".getBytes(UTF_8)).pack();
    byte[] both = new References().add("foobar".getBytes(UTF_8)).addAll(first).pack();

    ByteBuffer buffer = ByteBuffer.wrap(both).order(ByteOrder.LITTLE_ENDIAN);
    assertThat(buffer.getLong()).isEqualTo(0x85944171f73967e8L);
    assertThat(buffer.getLong()).isEqualTo(0xaf63dc4c8601ec8cL);
  }
}
