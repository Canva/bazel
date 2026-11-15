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
package com.google.devtools.build.lib.skyframe;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.TopLevelArtifactContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.SkyValueRetrieverUtils.CompletedProbe;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievalResult;
import com.google.devtools.build.lib.skyframe.serialization.SkyValueRetriever.RetrievedValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedPackageKey;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedPackageValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedTopLevelKey;
import com.google.devtools.build.lib.skyframe.serialization.analysis.CompletedTopLevelValue;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ExecutionValueCaching;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheReaderDepsProvider;
import com.google.devtools.build.lib.skyframe.serialization.analysis.TopLevelAdmission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

/**
 * Asks the remote analysis cache, once for each package and before any top-level target is
 * evaluated, which targets of the package were built with their aspects: see {@link
 * CompletedPackageKey}.
 *
 * <p>What a valid record says is kept where the function that evaluates a top-level key looks for
 * what was found about that key, so it finds the answer there and asks nothing. A target the
 * records do not answer for is asked about as before.
 */
public final class BuiltPackages {

  /**
   * @param targets the top-level targets of the build
   * @param aspects the keys that name the top-level aspects of each, or none
   * @param willTest whether the target with a label is going to be tested, which a record of
   *     what was built does not answer for
   * @return the targets that are recorded as built with all their aspects. Nothing needs to be
   *     evaluated for the aspects of one of these.
   */
  public static ImmutableSet<ConfiguredTargetKey> ask(
      List<ConfiguredTargetKey> targets,
      List<TopLevelAspectsKey> aspects,
      TopLevelArtifactContext context,
      Predicate<Label> willTest,
      RemoteAnalysisCacheReaderDepsProvider cachingDeps)
      throws InterruptedException {
    TopLevelAdmission admission = ExecutionValueCaching.topLevelAdmission();
    if (admission == null
        || !admission.withoutValues()
        || !admission.byPackage()
        || !cachingDeps.mode().isRetrievalEnabled()
        || cachingDeps.getSkycacheAnalysisOnly()) {
      return ImmutableSet.of();
    }
    String aspectsName = aspects.isEmpty() ? "" : aspects.get(0).getAspectsName();
    var aspectsOf = new HashMap<ConfiguredTargetKey, TopLevelAspectsKey>();
    for (TopLevelAspectsKey key : aspects) {
      aspectsOf.put(key.getBaseConfiguredTargetKey(), key);
    }
    var byPackage = new HashMap<PackageIdentifier, List<ConfiguredTargetKey>>();
    for (ConfiguredTargetKey target : targets) {
      Label label = target.getLabel();
      if (label == null || willTest.test(label)) {
        continue;
      }
      byPackage.computeIfAbsent(label.getPackageIdentifier(), unused -> new ArrayList<>())
          .add(target);
    }
    if (byPackage.isEmpty()) {
      return ImmutableSet.of();
    }

    var packages = new ArrayList<List<ConfiguredTargetKey>>(byPackage.size());
    var lookups = new ArrayList<ListenableFuture<RetrievalResult>>(byPackage.size());
    for (Map.Entry<PackageIdentifier, List<ConfiguredTargetKey>> entry : byPackage.entrySet()) {
      packages.add(entry.getValue());
      lookups.add(
          SkyValueRetrieverUtils.retrieveDetached(
              CompletedPackageKey.of(entry.getKey(), context, aspectsName), cachingDeps));
    }
    try {
      Futures.whenAllComplete(lookups).call(() -> null, MoreExecutors.directExecutor()).get();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }

    int valid = 0;
    ImmutableSet.Builder<ConfiguredTargetKey> answered = ImmutableSet.builder();
    for (int i = 0; i < lookups.size(); i++) {
      RetrievalResult result;
      try {
        result = Futures.getDone(lookups.get(i));
      } catch (ExecutionException e) {
        continue; // As if there were no record.
      }
      if (!(result instanceof RetrievedValue(var value))
          || !(value instanceof CompletedPackageValue built)) {
        continue;
      }
      valid++;
      var builtWith = new HashMap<ActionLookupKey, ActionLookupKey>();
      for (int j = 0; j < built.requested().size(); j++) {
        builtWith.put(built.requested().get(j), built.builtWith().get(j));
      }
      for (ConfiguredTargetKey target : packages.get(i)) {
        ActionLookupKey key = builtWith.get(target);
        if (key == null) {
          continue;
        }
        answered.add(target);
        admission.shareProbe(
            CompletedTopLevelKey.of(target, context, /* willTest= */ false),
            found(CompletedTopLevelValue.builtWith(key)));
        TopLevelAspectsKey aspectsKey = aspectsOf.get(target);
        if (aspectsKey != null) {
          admission.shareProbe(
              CompletedTopLevelKey.of(aspectsKey, context, /* willTest= */ false),
              found(CompletedTopLevelValue.INSTANCE));
          admission.noteAspectsOfATargetBuilt(aspectsKey);
        }
      }
    }
    ImmutableSet<ConfiguredTargetKey> built = answered.build();
    admission.noteBuiltByPackage(lookups.size(), valid, built.size());
    return built;
  }

  private static CompletedProbe found(CompletedTopLevelValue value) {
    return new CompletedProbe(new RetrievedValue(value), ImmutableMap.of());
  }

  private BuiltPackages() {}
}
