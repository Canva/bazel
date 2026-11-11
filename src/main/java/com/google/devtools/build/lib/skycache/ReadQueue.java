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

import com.google.common.util.concurrent.ListenableFuture;

/** Reads entries from the analysis cache service, one key at a time as Bazel asks for them. */
interface ReadQueue {

  /**
   * Asks for a key.
   *
   * @return the stored value, or {@code null} if the service has no entry for the key. Callers
   *     translate absence into whatever their own contract expects.
   */
  ListenableFuture<byte[]> enqueue(byte[] key);

  /**
   * Asks for a key without asking for the entries its entry references, for a reader that has yet
   * to decide whether it will use the entry. The same as {@link #enqueue} for a queue that only
   * ever reads the keys it is given.
   */
  default ListenableFuture<byte[]> enqueueUnexpanded(byte[] key) {
    return enqueue(key);
  }

  /** Number of requests actually issued, as against the number of keys asked for. */
  long batchesSent();

  /** Reads answered as misses because the in-flight cap was reached. */
  long shed();

  /** Releases whatever the queue holds for the command that is ending. */
  default void close() {}
}
