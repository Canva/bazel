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

import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.Path;

/**
 * Reports every path as never having changed.
 *
 * <p>{@link LongVersionGetter} is the oracle the frontier serializer consults to decide, for each
 * source path a cached node depended on, the version at which that path last changed. Those numbers
 * drive invalidation on download: a cached entry survives only if nothing it read has moved since.
 * Bazel ships no implementation outside tests, because inside Google the answer comes from the
 * version control snapshot.
 *
 * <p>Answering {@link LongVersionGetter#MINIMAL} everywhere asserts that no source file has ever
 * changed, which disables invalidation entirely. That is sound ONLY when the uploading and
 * downloading workspaces are known to hold identical content — the controlled condition this
 * experiment runs under. It is wrong for any real deployment: a stale entry would be served as a
 * hit. A production implementation has to derive versions from something that actually tracks
 * content, the repository's commit history being the obvious candidate.
 */
final class UnchangedLongVersionGetter implements LongVersionGetter {

  static final UnchangedLongVersionGetter INSTANCE = new UnchangedLongVersionGetter();

  private UnchangedLongVersionGetter() {}

  @Override
  public long getFilePathOrSymlinkVersion(Path path) {
    return MINIMAL;
  }

  @Override
  public long getDirectoryListingVersion(Path path) {
    return MINIMAL;
  }

  @Override
  public long getNonexistentPathVersion(Path path) {
    return MINIMAL;
  }
}
