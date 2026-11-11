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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Packs the keys of the entries a stored entry references, for {@link ReferencingWriter#put}.
 *
 * <p>The packed form is each key preceded by its length in one byte. Keys are at most {@link
 * #MAX_KEY_BYTES} long: fingerprints are 16 bytes, and the longest invalidation data key is
 * shorter than a byte can count.
 *
 * <p>Not thread-safe.
 */
public final class References {
  /** The longest key that can be packed. */
  public static final int MAX_KEY_BYTES = 255;

  private byte[] packed;
  private int size;

  public References() {
    this(/* expectedKeys= */ 4);
  }

  public References(int expectedKeys) {
    this.packed = new byte[Math.max(1, expectedKeys) * (PackedFingerprint.BYTES + 1)];
  }

  /** Adds the fingerprint at {@code offset} in {@code bytes}. */
  public References addFingerprintAt(byte[] bytes, int offset) {
    ensureCapacity(PackedFingerprint.BYTES + 1);
    packed[size++] = PackedFingerprint.BYTES;
    System.arraycopy(bytes, offset, packed, size, PackedFingerprint.BYTES);
    size += PackedFingerprint.BYTES;
    return this;
  }

  public References add(PackedFingerprint fingerprint) {
    ensureCapacity(PackedFingerprint.BYTES + 1);
    packed[size++] = PackedFingerprint.BYTES;
    fingerprint.copyTo(packed, size);
    size += PackedFingerprint.BYTES;
    return this;
  }

  public References add(byte[] key) {
    checkArgument(key.length > 0 && key.length <= MAX_KEY_BYTES, "key of %s bytes", key.length);
    ensureCapacity(key.length + 1);
    packed[size++] = (byte) key.length;
    System.arraycopy(key, 0, packed, size, key.length);
    size += key.length;
    return this;
  }

  public References add(KeyBytesProvider key) {
    return key instanceof PackedFingerprint fingerprint ? add(fingerprint) : add(key.toBytes());
  }

  /** Adds every key in {@code packed}, which may be null. */
  public References addAll(@Nullable byte[] packed) {
    if (packed != null && packed.length > 0) {
      ensureCapacity(packed.length);
      System.arraycopy(packed, 0, this.packed, size, packed.length);
      size += packed.length;
    }
    return this;
  }

  public boolean isEmpty() {
    return size == 0;
  }

  /** Returns the packed keys, or null if there are none. */
  @Nullable
  public byte[] pack() {
    return size == 0 ? null : Arrays.copyOf(packed, size);
  }

  private void ensureCapacity(int extra) {
    if (size + extra > packed.length) {
      packed = Arrays.copyOf(packed, Math.max(packed.length * 2, size + extra));
    }
  }
}
