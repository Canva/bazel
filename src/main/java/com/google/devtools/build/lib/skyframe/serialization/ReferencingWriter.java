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

import javax.annotation.Nullable;

/**
 * A store that can be told, with an entry, which entries a reader of it will read next.
 *
 * <p>A reader learns which nested values an entry needs only by deserializing it, so each level
 * of nesting costs it another request and another round trip. A store that knows what an entry
 * references can answer a read with the referenced entries as well, sparing the reader those.
 *
 * <p>Separate from {@link FingerprintValueStore} and {@code KeyValueWriter} so that neither
 * changes: a store that cannot follow references does not implement this, and writers fall back to
 * the plain {@code put}.
 */
public interface ReferencingWriter {

  /**
   * Stores {@code serializedBytes} under {@code key}, as {@link FingerprintValueStore#put} does.
   *
   * @param references a reference to each entry a reader of this one reads next, as {@code
   *     References} packs them, or null if there are none. A hint: the store may ignore it, and a
   *     writer may leave entries out.
   */
  WriteStatus put(KeyBytesProvider key, byte[] serializedBytes, @Nullable byte[] references);
}
