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

import com.google.common.util.concurrent.ListenableFuture;
import java.io.IOException;

/**
 * A store that can be asked for an entry alone, where an ordinary read may bring the entries it
 * references as well.
 *
 * <p>A store that follows references answers a read with the closure of the entry, which is what
 * a reader about to deserialize the entry wants. A reader that first has to decide whether to use
 * the entry at all does not: the closure of an entry it then rejects was sent for nothing. With a
 * widely loaded {@code .bzl} file edited, a build of the Canva monorepo rejected 70% of the
 * entries it looked up, and 4.2 GB of the 4.75 GB it had been sent was never read.
 *
 * <p>Separate from {@link FingerprintValueStore} so that it does not change: a store without
 * this is simply read with {@link FingerprintValueStore#get}.
 */
public interface UnexpandedReader {

  /**
   * Retrieves the bytes stored under {@code key}, as {@link FingerprintValueStore#get} does,
   * without asking for anything the entry references.
   */
  ListenableFuture<byte[]> getUnexpanded(KeyBytesProvider key) throws IOException;
}
