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
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;

/**
 * The key under which the remote analysis cache records which top-level targets of one package a
 * build built completely, together with all their top-level aspects.
 *
 * <p>It says for many targets at once what a {@link CompletedTopLevelKey} says for one, and its
 * file dependencies are those of all of them. A reader of a build of a whole repository asks this
 * once for each package before it asks anything about a target, and a package nothing has changed
 * under answers for every target in it. One that something has changed under answers for none of
 * them, and the reader then asks about each as it would have.
 *
 * <p>Like {@link CompletedTopLevelKey} it is a key of the cache only, never of a node.
 *
 * @param packageId the package
 * @param outputGroups the output groups asked for, sorted
 * @param aspects what names the build's top-level aspects and their parameters, or an empty
 *     string for a build without any: the same for every target of the build
 */
@AutoCodec
public record CompletedPackageKey(
    PackageIdentifier packageId,
    ImmutableList<String> outputGroups,
    boolean runTestsExclusively,
    boolean forRunCommand,
    String aspects)
    implements SkyKey {
  private static final SkyFunctionName COMPLETED_PACKAGE =
      SkyFunctionName.createHermetic("COMPLETED_PACKAGE");

  public static CompletedPackageKey of(
      PackageIdentifier packageId, TopLevelArtifactContext context, String aspects) {
    return new CompletedPackageKey(
        packageId,
        // Already sorted: the context keeps them in a sorted set.
        ImmutableList.copyOf(context.outputGroups()),
        context.runTestsExclusively(),
        context.forRunCommand(),
        aspects);
  }

  @Override
  public SkyFunctionName functionName() {
    return COMPLETED_PACKAGE;
  }
}
