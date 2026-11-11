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

import static com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.immediateWriteStatus;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider;
import com.google.devtools.build.lib.skyframe.serialization.UnexpandedReader;
import com.google.devtools.build.lib.skyframe.serialization.WriteStatus;
import java.io.IOException;

/**
 * The store of a command that only reads: writes are accepted and dropped.
 *
 * <p>A reader still writes. To look a value up, Bazel serializes its key the way a writer would,
 * and serializing a key puts the shared values it is built from, its label and configuration
 * among them, whatever the command's mode. A read of the whole monorepo sent 1.2 million such
 * entries. Nothing reads them back: only the key's fingerprint is used, and that is computed
 * locally. Against the service they are also refused, since a reader is not a writer there, and a
 * refused write is reported as a failed upload by a command that uploads nothing.
 */
final class ReadOnlyFingerprintValueStore implements FingerprintValueStore, UnexpandedReader {

  private final FingerprintValueStore delegate;

  ReadOnlyFingerprintValueStore(FingerprintValueStore delegate) {
    this.delegate = delegate;
  }

  @Override
  public WriteStatus put(KeyBytesProvider fingerprint, byte[] serializedBytes) {
    return immediateWriteStatus();
  }

  @Override
  public ListenableFuture<byte[]> get(KeyBytesProvider fingerprint) throws IOException {
    return delegate.get(fingerprint);
  }

  @Override
  public ListenableFuture<byte[]> getUnexpanded(KeyBytesProvider fingerprint) throws IOException {
    return delegate instanceof UnexpandedReader unexpanded
        ? unexpanded.getUnexpanded(fingerprint)
        : delegate.get(fingerprint);
  }

  @Override
  public Stats getStats() {
    return delegate.getStats();
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }
}
