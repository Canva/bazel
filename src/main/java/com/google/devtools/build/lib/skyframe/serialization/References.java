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

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.Arrays;
import javax.annotation.Nullable;

/**
 * Packs references to the entries a stored entry references, for {@link ReferencingWriter#put}.
 *
 * <p>A reference is the 64-bit FNV-1a hash of the referenced entry's key, and the packed form is
 * those hashes one after another, eight bytes each, least significant byte first. A hash rather
 * than the key because references are only a hint for what to send a reader ahead of its asking:
 * two keys with one hash cost a reader an entry it did not need, never a wrong one. In an upload
 * of the Canva monorepo the keys themselves came to 9.3 GB beside 20 GB of values, most of it the
 * path-like keys of invalidation data that the values name too.
 *
 * <p>Not thread-safe.
 */
public final class References {
  /** Bytes one packed reference takes. */
  public static final int BYTES = 8;

  private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  private byte[] packed;
  private int size;

  public References() {
    this(/* expectedKeys= */ 4);
  }

  public References(int expectedKeys) {
    this.packed = new byte[Math.max(1, expectedKeys) * BYTES];
  }

  /** Returns the reference to the entry stored under {@code length} bytes of {@code key}. */
  public static long hash(byte[] key, int offset, int length) {
    long hash = FNV_OFFSET_BASIS;
    for (int i = offset; i < offset + length; i++) {
      hash ^= key[i] & 0xff;
      hash *= FNV_PRIME;
    }
    return hash;
  }

  /** Adds a reference to the entry whose key is the fingerprint at {@code offset} in {@code bytes}. */
  @CanIgnoreReturnValue
  public References addFingerprintAt(byte[] bytes, int offset) {
    return addHash(hash(bytes, offset, PackedFingerprint.BYTES));
  }

  @CanIgnoreReturnValue
  public References add(PackedFingerprint fingerprint) {
    return add(fingerprint.toBytes());
  }

  @CanIgnoreReturnValue
  public References add(byte[] key) {
    return addHash(hash(key, 0, key.length));
  }

  @CanIgnoreReturnValue
  public References add(KeyBytesProvider key) {
    return add(key.toBytes());
  }

  /** Adds every reference in {@code packed}, which may be null. */
  @CanIgnoreReturnValue
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

  /** Returns the packed references, or null if there are none. */
  @Nullable
  public byte[] pack() {
    return size == 0 ? null : Arrays.copyOf(packed, size);
  }

  private References addHash(long hash) {
    ensureCapacity(BYTES);
    for (int i = 0; i < BYTES; i++) {
      packed[size++] = (byte) (hash >>> (8 * i));
    }
    return this;
  }

  private void ensureCapacity(int extra) {
    if (size + extra > packed.length) {
      packed = Arrays.copyOf(packed, Math.max(packed.length * 2, size + extra));
    }
  }
}
