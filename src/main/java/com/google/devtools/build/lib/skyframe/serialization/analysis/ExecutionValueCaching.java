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

import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

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

  private static volatile boolean testResults = false;

  /**
   * Sets whether the result of a test that passed is written too, and reported by a reader as a
   * cached pass without building the test's inputs: see {@code ExecutionValuesForReaders}.
   */
  public static void setTestResultsEnabled(boolean testResults) {
    ExecutionValueCaching.testResults = testResults;
  }

  public static boolean testResultsEnabled() {
    return testResults;
  }

  private static final ConcurrentHashMap<String, LongAdder> testResultOutcomes =
      new ConcurrentHashMap<>();
  private static final Set<Object> testResultsNoted = ConcurrentHashMap.newKeySet();

  /**
   * Records what became of a test result a reader looked up: that it was served, that the cache
   * had none, or why the test declined the one it had.
   */
  public static void noteTestResult(String outcome) {
    testResultOutcomes.computeIfAbsent(outcome, unused -> new LongAdder()).increment();
  }

  /**
   * As {@link #noteTestResult(String)}, once for each {@code test}: an evaluation that is started
   * again looks its result up again.
   */
  public static void noteTestResult(String outcome, Object test) {
    if (testResultsNoted.add(test)) {
      noteTestResult(outcome);
    }
  }

  /** The outcomes recorded since the last reset, most common first, or an empty string. */
  public static String testResultOutcomes() {
    return testResultOutcomes.entrySet().stream()
        .sorted(
            Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> -e.getValue().sum())
                .thenComparing(Map.Entry::getKey))
        .map(e -> e.getValue().sum() + " " + e.getKey())
        .collect(Collectors.joining(", "));
  }

  public static void resetTestResultOutcomes() {
    testResultOutcomes.clear();
    testResultsNoted.clear();
  }
}
