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
package com.google.devtools.build.lib.analysis.test;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyValue;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * What a build that takes a test's result from the remote analysis cache needs of the test's
 * target in order to report it: what the target says about its tests, each test action without
 * its inputs, and each action's result.
 *
 * <p>A test's target is otherwise fetched whole to report results the cache already holds: every
 * provider and every action, the runfiles of the test among them, which reach everything the test
 * depends on. For a build of many cached tests that was most of what a reader fetched and
 * deserialized.
 *
 * @param provider the target's test parameters, which name the status artifact of each action
 * @param actions the action of each status artifact, in that order: see {@link
 *     TestRunnerAction#withoutInputs}
 * @param results the result of each action, in the same order, as a reader takes it: each an
 *     {@code ActionExecutionValue}
 */
@AutoCodec
public record ServedTest(
    TestProvider provider,
    ImmutableList<TestRunnerAction> actions,
    ImmutableList<SkyValue> results) {

  /**
   * Returns what a reader needs to report the tests of {@code target}, or null if it has to fetch
   * the target: it is not a test, or one of its results is not there for a reader to take.
   *
   * @param actions the value of the target's node
   * @param resultForReader the result of an action as a reader takes it, or null if there is none
   */
  @Nullable
  public static ServedTest of(
      ConfiguredTarget target,
      ActionLookupValue actions,
      Function<ActionLookupData, ? extends SkyValue> resultForReader) {
    TestProvider provider = target.getProvider(TestProvider.class);
    if (provider == null || provider.getTestParams().getTestStatusArtifacts().isEmpty()) {
      return null;
    }
    ImmutableList.Builder<TestRunnerAction> slim = ImmutableList.builder();
    ImmutableList.Builder<SkyValue> results = ImmutableList.builder();
    for (Artifact.DerivedArtifact status : provider.getTestParams().getTestStatusArtifacts()) {
      ActionLookupData generating = status.getGeneratingActionKey();
      if (!generating.getActionLookupKey().equals(target.getLookupKey())
          || generating.getActionIndex() >= actions.getActions().size()
          || !(actions.getActions().get(generating.getActionIndex())
              instanceof TestRunnerAction action)) {
        return null;
      }
      SkyValue result = resultForReader.apply(generating);
      if (result == null) {
        return null;
      }
      slim.add(action.withoutInputs());
      results.add(result);
    }
    return new ServedTest(provider, slim.build(), results.build());
  }
}
