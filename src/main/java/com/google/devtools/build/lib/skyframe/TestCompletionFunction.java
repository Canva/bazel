// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableMultimap;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.analysis.test.ServedTest;
import com.google.devtools.build.lib.analysis.test.TestAttempt;
import com.google.devtools.build.lib.analysis.test.TestProvider;
import com.google.devtools.build.lib.analysis.test.TestResult;
import com.google.devtools.build.lib.analysis.test.TestRunnerAction;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.server.FailureDetails.Execution.Code;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ExecutionValueCaching;
import com.google.devtools.build.lib.skyframe.serialization.analysis.TopLevelAdmission;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.view.test.TestStatus.BlazeTestStatus;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.List;
import javax.annotation.Nullable;

/**
 * TestCompletionFunction builds all relevant test artifacts of a {@link
 * com.google.devtools.build.lib.analysis.ConfiguredTarget}. This includes test shards and repeated
 * runs.
 */
public final class TestCompletionFunction implements SkyFunction {
  private final SkyframeActionExecutor skyframeActionExecutor;

  TestCompletionFunction(SkyframeActionExecutor skyframeActionExecutor) {
    this.skyframeActionExecutor = skyframeActionExecutor;
  }

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    TestCompletionValue.TestCompletionKey key =
        (TestCompletionValue.TestCompletionKey) skyKey.argument();
    ConfiguredTargetKey ctKey = key.configuredTargetKey();
    TopLevelArtifactContext ctx = key.topLevelArtifactContext();
    if (env.getValue(TargetCompletionValue.key(ctKey, ctx, /* willTest= */ true)) == null) {
      return null;
    }

    // A reader of the analysis cache that found the results of this target's tests in the record
    // of the target reports them from there. Each is the result its action's node would retrieve
    // and report in the same way, and the record is valid exactly when they are; but the node
    // needs the action, which needs the target, and the target is most of what a test costs a
    // reader. See ServedTest.
    TopLevelAdmission admission = ExecutionValueCaching.topLevelAdmission();
    if (admission != null && admission.servedTest(ctKey) instanceof ServedTest served) {
      // The results stand for this build only as long as the record does, and nothing in the
      // graph ties this value to the sources: as for a target's completion, depend on the build.
      PrecomputedValue.BUILD_ID.get(env);
      if (env.valuesMissing()) {
        return null;
      }
      if (reportServed(served)) {
        admission.noteServedTestReported();
        return TestCompletionValue.TEST_COMPLETION_MARKER;
      }
      // Declined, with nothing reported: the target and its actions are asked for after all.
    }

    ConfiguredTargetValue ctValue = (ConfiguredTargetValue) env.getValue(ctKey);
    if (ctValue == null) {
      return null;
    }

    ConfiguredTarget ct = ctValue.getConfiguredTarget();
    if (key.exclusiveTesting()) {
      // Request test execution iteratively if testing exclusively.
      for (Artifact.DerivedArtifact testArtifact : TestProvider.getTestStatusArtifacts(ct)) {
        env.getValue(testArtifact.getGeneratingActionKey());
        if (env.valuesMissing()) {
          return null;
        }
      }
    } else {
      List<SkyKey> skyKeys = Artifact.keys(TestProvider.getTestStatusArtifacts(ct));
      SkyframeLookupResult result = env.getValuesAndExceptions(skyKeys);
      if (env.valuesMissing()) {
        return null;
      }
      for (SkyKey actionKey : skyKeys) {
        try {
          if (result.getOrThrow(actionKey, ActionExecutionException.class) == null) {
            return null;
          }
        } catch (ActionExecutionException e) {
          DetailedExitCode detailedExitCode = e.getDetailedExitCode();
          if (detailedExitCode.getExitCode().equals(ExitCode.BUILD_FAILURE)
              && ctValue instanceof ActionLookupValue actionLookupValue) {
            postTestResultEventsForBuiltTestThatCouldNotBeRun(
                env, (ActionLookupData) actionKey, actionLookupValue, detailedExitCode);
          } else {
            return null;
          }
        }
      }
    }
    return TestCompletionValue.TEST_COMPLETION_MARKER;
  }

  /**
   * Reports each result of {@code served} as its action's node would on retrieving it. Returns
   * false, having reported nothing, if a test does not take the result it has.
   */
  private boolean reportServed(ServedTest served) {
    for (int i = 0; i < served.actions().size(); i++) {
      if (!(served.results().get(i) instanceof ActionExecutionValue result)
          || served.actions().get(i).whyRetrievedResultIsDeclined(result.getAllFileValues()::get)
              != null) {
        return false;
      }
    }
    boolean all = true;
    for (int i = 0; i < served.actions().size(); i++) {
      if (skyframeActionExecutor.notifyResultRetrieved(
          served.actions().get(i), (ActionExecutionValue) served.results().get(i))) {
        ExecutionValueCaching.noteTestResult("served");
      } else {
        all = false;
      }
    }
    // Each was asked whether it takes its result before any was told to.
    return all;
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((ConfiguredTargetKey) skyKey.argument()).getLabel());
  }

  /**
   * Posts events for test actions that could not run despite the fact that the test target built
   * successfully.
   *
   * <p>When we run this SkyFunction we will have already built the test executable and its inputs,
   * but we might be unable to run the test. The currently known scenarios where this happens are:
   *
   * <ol>
   *   <li>A failure to build the exec-configured attributes providing inputs to the {@link
   *       TestRunnerAction} such as {@code $test_runtime}, {@code $test_wrapper}, {@code
   *       test_setup_script} and others.
   *   <li>The test strategy throws an {@link ExecException} prior to running the test, for example
   *       when some sort of validation fails.
   *   <li>The test action observes lost input(s) and initiates action rewinding, but the lost
   *       input(s) fail to build. Note that this implies action nondeterminism, since the lost
   *       input(s) were previously built successfully.
   * </ol>
   *
   * <p>In these scenarios, we do not get to use any {@code TestStrategy} that is responsible for
   * posting {@link TestAttempt} and {@link TestResult} events. We need to post minimal events here
   * indicating the test {@link BlazeTestStatus#FAILED_TO_BUILD FAILED_TO_BUILD}.
   */
  private static void postTestResultEventsForBuiltTestThatCouldNotBeRun(
      Environment env,
      ActionLookupData actionKey,
      ActionLookupValue actionLookupValue,
      DetailedExitCode detailedExitCode) {
    BlazeTestStatus status = BlazeTestStatus.FAILED_TO_BUILD;
    if (detailedExitCode
        .getFailureDetail()
        .getExecution()
        .getCode()
        .equals(Code.ACTION_NOT_UP_TO_DATE)) {
      status = BlazeTestStatus.NO_STATUS;
    }
    TestRunnerAction testRunnerAction =
        (TestRunnerAction) actionLookupValue.getAction(actionKey.getActionIndex());
    TestResultData testData = TestResultData.newBuilder().setStatus(status).build();
    env.getListener().post(TestAttempt.forUnstartableTestResult(testRunnerAction, testData));
    env.getListener()
        .post(
            new TestResult(
                testRunnerAction,
                testData,
                ImmutableMultimap.of(),
                /* cached= */ false,
                detailedExitCode));
  }
}
