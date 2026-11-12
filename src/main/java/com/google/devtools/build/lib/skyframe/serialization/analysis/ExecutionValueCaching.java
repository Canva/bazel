// Copyright 2024 The Bazel Authors. All rights reserved.
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

/**
 * Whether the remote analysis cache holds execution values: action results and the artifacts they
 * produced, alongside the analysis values.
 *
 * <p>An execution value describes outputs that exist only in the output base, or the remote cache,
 * of the build that produced it; some, such as a symlink resolving into {@code bazel-out}, cannot
 * be serialized at all. Blaze avoids them with {@code --experimental_skycache_analysis_only}, which
 * Bazel's executor refuses because it also needs per-value source versions Bazel's graph does not
 * keep. With this off, uploads leave execution values out and downloads never look them up, so
 * every action runs, or hits the action and remote caches, as it would without the analysis cache.
 *
 * <p>On by default, as a stock build has it. Set per command by whoever configures the cache.
 */
public final class ExecutionValueCaching {

  private static volatile boolean enabled = true;

  private ExecutionValueCaching() {}

  public static void setEnabled(boolean enabled) {
    ExecutionValueCaching.enabled = enabled;
  }

  public static boolean isEnabled() {
    return enabled;
  }

  private static volatile boolean localResults = true;

  /**
   * Sets whether the result of an action whose outputs exist only on the writer's disk is written
   * too, described to readers as remote: see {@code ExecutionValuesForReaders}.
   */
  public static void setLocalResultsEnabled(boolean localResults) {
    ExecutionValueCaching.localResults = localResults;
  }

  public static boolean localResultsEnabled() {
    return localResults;
  }
}
