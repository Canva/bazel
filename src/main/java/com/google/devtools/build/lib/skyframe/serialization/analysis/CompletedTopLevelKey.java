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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * The key under which the remote analysis cache records that a top-level target or aspect was
 * built completely: every artifact in the output groups a build asked of it.
 *
 * <p>It is a key of the cache only, never of a node. A writer stores a {@link
 * CompletedTopLevelValue} under it for each target and aspect it completed, with the file
 * dependencies of the target or aspect, sources included, as it does for the results of their
 * actions. A reader that finds the entry valid knows that building the same artifacts again would
 * run the same actions on the same inputs, and can report the target as built without asking for
 * a single one of them. What it gives up is the artifacts: it has no metadata to list them in a
 * build event or to fetch them, so this is for builds that want to know whether everything builds
 * and passes, not ones that use the outputs.
 *
 * <p>The fields are those of the completion key of the build graph, spelt out so that the key can
 * be serialized.
 *
 * @param owner the configured target or aspect
 * @param outputGroups the output groups asked for, sorted
 * @param willTest whether the target was built to be run as a test, which for a writer that also
 *     records test results means without its runfiles tree
 */
@AutoCodec
public record CompletedTopLevelKey(
    ActionLookupKey owner,
    ImmutableList<String> outputGroups,
    boolean runTestsExclusively,
    boolean forRunCommand,
    boolean willTest)
    implements SkyKey {

  private static final SkyFunctionName COMPLETED_TOP_LEVEL =
      SkyFunctionName.createHermetic("COMPLETED_TOP_LEVEL");

  public static CompletedTopLevelKey of(
      ActionLookupKey owner, TopLevelArtifactContext context, boolean willTest) {
    return new CompletedTopLevelKey(
        owner,
        // Already sorted: the context keeps them in a sorted set.
        ImmutableList.copyOf(context.outputGroups()),
        context.runTestsExclusively(),
        context.forRunCommand(),
        willTest);
  }

  @Override
  public SkyFunctionName functionName() {
    return COMPLETED_TOP_LEVEL;
  }
}
