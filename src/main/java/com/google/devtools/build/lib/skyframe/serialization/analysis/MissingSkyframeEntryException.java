// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.devtools.build.skyframe.SkyKey;

/**
 * Exception thrown when an expected value is missing from Skyframe.
 *
 * <p>This occurs when the Bazel build completes but writing is still underway.
 */
final class MissingSkyframeEntryException extends Exception {
  private final SkyKey key;
  private final boolean inError;

  MissingSkyframeEntryException(SkyKey key) {
    super(key.getCanonicalName() + " was missing");
    this.key = key;
    this.inError = false;
  }

  private MissingSkyframeEntryException(SkyKey key, String message) {
    super(message);
    this.key = key;
    this.inError = true;
  }

  /**
   * For an entry that is there but ended in an error, in a build that kept going.
   *
   * <p>Something that depends on it may have a value all the same, but what would invalidate that
   * cannot be told in full, so it is left out of the upload: see {@link #isAnEntryInError}.
   */
  static MissingSkyframeEntryException inError(SkyKey key) {
    return new MissingSkyframeEntryException(key, key.getCanonicalName() + " is in error");
  }

  /** Whether {@code t} is, or was caused by, an exception from {@link #inError}. */
  static boolean isAnEntryInError(Throwable t) {
    for (Throwable cause = t; cause != null; cause = cause.getCause()) {
      if (cause instanceof MissingSkyframeEntryException missing && missing.inError) {
        return true;
      }
    }
    return false;
  }

  SkyKey key() {
    return key;
  }
}
