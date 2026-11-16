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
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * What the remote analysis cache holds under a {@link CompletedPackageKey}: the targets of the
 * package that were built, each with all its top-level aspects, and for those that were tested,
 * the results of their tests.
 *
 * <p>A target is given by its name and by the configurations of the key it was asked for with and
 * of the key it was built with, which with the package make both keys. Spelled out, the keys of a
 * build of half a million targets were a million values for a reader to fetch.
 *
 * @param formatVersion to be raised if what an entry promises ever changes
 * @param names the name of each target, in order
 * @param configurations the configuration keys the targets' keys have, each once
 * @param requestedConfiguration for each target, where in {@code configurations} the
 *     configuration of the key it was asked for with is. That key applies the rule's transition
 *     and names no execution platform, as the key of a top-level target does
 * @param builtConfiguration for each target, the same for the key it was built with, which names
 *     no execution platform either: see {@link CompletedTopLevelValue#builtWith}
 * @param builtAppliesRuleTransition for each target, whether the key it was built with applies
 *     the rule's transition
 * @param servedTests for each target, a {@code ServedTest} if it was tested: see {@link
 *     CompletedTopLevelValue#servedTest}. False if it was built and not tested
 */
@AutoCodec
public record CompletedPackageValue(
    int formatVersion,
    ImmutableList<String> names,
    ImmutableList<Object> configurations,
    ImmutableList<Integer> requestedConfiguration,
    ImmutableList<Integer> builtConfiguration,
    ImmutableList<Boolean> builtAppliesRuleTransition,
    ImmutableList<Object> servedTests)
    implements DeserializedSkyValue {
  public static CompletedPackageValue of(
      ImmutableList<String> names,
      ImmutableList<Object> configurations,
      ImmutableList<Integer> requestedConfiguration,
      ImmutableList<Integer> builtConfiguration,
      ImmutableList<Boolean> builtAppliesRuleTransition,
      ImmutableList<Object> servedTests) {
    return new CompletedPackageValue(
        2,
        names,
        configurations,
        requestedConfiguration,
        builtConfiguration,
        builtAppliesRuleTransition,
        servedTests);
  }
}
