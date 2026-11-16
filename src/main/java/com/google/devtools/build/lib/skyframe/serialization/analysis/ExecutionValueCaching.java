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
import javax.annotation.Nullable;

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

  private static volatile boolean completions = false;

  /**
   * Sets whether a writer records the top-level targets and aspects it built completely, and a
   * reader reports one whose record is valid as built without asking for its artifacts: see
   * {@code CompletedTopLevelKey}.
   */
  public static void setCompletionsEnabled(boolean completions) {
    ExecutionValueCaching.completions = completions;
  }

  public static boolean completionsEnabled() {
    return completions;
  }

  @Nullable private static volatile TopLevelAdmission topLevelAdmission;

  /**
   * Sets how many top-level keys with nothing to build may fetch their values at a time, for the
   * command that is starting; zero or less for no limit, and no asking first whether they were
   * built. See {@link TopLevelAdmission}.
   */
  public static void resetTopLevelAdmission(int window) {
    resetTopLevelAdmission(window, /* withoutValues= */ false);
  }

  /**
   * @param withoutValues whether a reader also reports a target or aspect that is recorded as
   *     built without fetching it
   */
  public static void resetTopLevelAdmission(int window, boolean withoutValues) {
    resetTopLevelAdmission(window, withoutValues, /* byPackage= */ false);
  }

  /**
   * @param byPackage whether a reader that reports targets without fetching them first asks,
   *     once for each package, which of its targets were built
   */
  public static void resetTopLevelAdmission(int window, boolean withoutValues, boolean byPackage) {
    resetTopLevelAdmission(window, withoutValues, byPackage, /* servedTests= */ false);
  }

  /**
   * @param servedTests whether a reader that reports targets without fetching them does the same
   *     for a test whose results are in its record
   */
  public static void resetTopLevelAdmission(
      int window, boolean withoutValues, boolean byPackage, boolean servedTests) {
    topLevelAdmission =
        window > 0
            ? new TopLevelAdmission(window, withoutValues, byPackage, servedTests)
            : null;
  }

  /** The admission of this command's top-level keys, or null if they are not ordered. */
  @Nullable
  public static TopLevelAdmission topLevelAdmission() {
    return completions && enabled ? topLevelAdmission : null;
  }

  /**
   * Whether this command reports the target or aspect of the given key as built from its record,
   * without its value: what is left to do for it then has nothing to execute or to wait for.
   */
  public static boolean builtWithoutItsValue(Object actionLookupKey) {
    TopLevelAdmission admission = topLevelAdmission();
    return admission != null && admission.builtValue(actionLookupKey) != null;
  }

  /** How this command's top-level keys were ordered, or an empty string if they were not. */
  public static String topLevelAdmissionSummary() {
    TopLevelAdmission admission = topLevelAdmission;
    return admission == null ? "" : admission.describe();
  }

  private static final ConcurrentHashMap<String, LongAdder> completionOutcomes =
      new ConcurrentHashMap<>();
  private static final Set<Object> completionsNoted = ConcurrentHashMap.newKeySet();

  /**
   * Counts what became of a reader's attempt to take a completed target or aspect from the cache.
   * Counted once for a key, since the function that asks is restarted.
   */
  public static void noteCompletion(String outcome, Object key) {
    if (completionsNoted.add(key)) {
      completionOutcomes.computeIfAbsent(outcome, unused -> new LongAdder()).increment();
    }
  }

  /** The outcomes counted since the last reset, as "N outcome, M outcome", or empty. */
  public static String completionOutcomes() {
    return completionOutcomes.entrySet().stream()
        .sorted(java.util.Map.Entry.comparingByKey())
        .map(e -> e.getValue().sum() + " " + e.getKey())
        .collect(Collectors.joining(", "));
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
    return summarize(testResultOutcomes, Integer.MAX_VALUE);
  }

  private static final ConcurrentHashMap<String, LongAdder> actionsRun = new ConcurrentHashMap<>();
  private static final Set<Object> actionsRunNoted = ConcurrentHashMap.newKeySet();

  /**
   * Records that a reader runs {@code action} although it takes action results from the cache.
   * {@code what} is the action's mnemonic and the reason, and each action counts once.
   */
  public static void noteActionRun(String what, Object action) {
    if (actionsRunNoted.add(action)) {
      actionsRun.computeIfAbsent(what, unused -> new LongAdder()).increment();
    }
  }

  /** The {@code limit} most common kinds of action a reader ran, or an empty string. */
  public static String actionsRun(int limit) {
    return summarize(actionsRun, limit);
  }

  private static String summarize(ConcurrentHashMap<String, LongAdder> counts, int limit) {
    return counts.entrySet().stream()
        .sorted(
            Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> -e.getValue().sum())
                .thenComparing(Map.Entry::getKey))
        .limit(limit)
        .map(e -> e.getValue().sum() + " " + e.getKey())
        .collect(Collectors.joining(", "));
  }

  public static void resetTestResultOutcomes() {
    completionOutcomes.clear();
    completionsNoted.clear();
    testResultOutcomes.clear();
    testResultsNoted.clear();
    actionsRun.clear();
    actionsRunNoted.clear();
  }
}
