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
import com.google.devtools.build.lib.analysis.test.ServedTest;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.skyframe.AspectKeyCreator.TopLevelAspectsKey;
import com.google.devtools.build.lib.skyframe.SkyValueRetrieverUtils.CompletedProbe;
import com.google.devtools.build.lib.skyframe.config.BuildConfigurationKey;
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
      // A record spells the key a target is asked for with as a top-level target's key is.
      if (label == null
          || target.getConfigurationKey() == null
          || target.getExecutionPlatformLabel() != null
          || !target.shouldApplyRuleTransition()) {
        continue;
      }
      byPackage.computeIfAbsent(label.getPackageIdentifier(), unused -> new ArrayList<>())
          .add(target);
    }
    if (byPackage.isEmpty()) {
      return ImmutableSet.of();
    }

    SilentCloseable issuing = Profiler.instance().profile("BuiltPackages.ask: issue");
    var packages = new ArrayList<List<ConfiguredTargetKey>>(byPackage.size());
    var lookups = new ArrayList<ListenableFuture<RetrievalResult>>(byPackage.size());
    for (Map.Entry<PackageIdentifier, List<ConfiguredTargetKey>> entry : byPackage.entrySet()) {
      packages.add(entry.getValue());
      lookups.add(
          SkyValueRetrieverUtils.retrieveDetached(
              CompletedPackageKey.of(entry.getKey(), context, aspectsName), cachingDeps));
    }
    issuing.close();
    try (SilentCloseable c = Profiler.instance().profile("BuiltPackages.ask: wait")) {
      Futures.whenAllComplete(lookups).call(() -> null, MoreExecutors.directExecutor()).get();
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
    SilentCloseable applying = Profiler.instance().profile("BuiltPackages.ask: apply");

    int valid = 0;
    int tests = 0;
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
      var indexOf = new HashMap<String, Integer>();
      for (int j = 0; j < built.names().size(); j++) {
        indexOf.put(built.names().get(j), j);
      }
      for (ConfiguredTargetKey target : packages.get(i)) {
        Integer index = indexOf.get(target.getLabel().getName());
        if (index == null
            || !target
                .getConfigurationKey()
                .equals(built.configurations().get(built.requestedConfiguration().get(index)))
            || !(built.configurations().get(built.builtConfiguration().get(index))
                instanceof BuildConfigurationKey builtConfiguration)) {
          continue;
        }
        // A target that was built to be tested was built without the runfiles tree a target
        // that is only built has, and one that was only built has no results: the record
        // answers for what the writer did with the target, and for nothing else.
        boolean tested = built.servedTests().get(index) instanceof ServedTest;
        if (tested != willTest.test(target.getLabel())) {
          continue;
        }
        ConfiguredTargetKey key =
            ConfiguredTargetKey.builder()
                .setLabel(target.getLabel())
                .setConfigurationKey(builtConfiguration)
                .setShouldApplyRuleTransition(built.builtAppliesRuleTransition().get(index))
                .build();
        TopLevelAspectsKey aspectsKey = aspectsOf.get(target);
        if (aspectsKey != null) {
          admission.shareProbe(
              CompletedTopLevelKey.of(aspectsKey, context, /* willTest= */ false),
              found(CompletedTopLevelValue.INSTANCE));
        }
        if (tested) {
          // The aspects of a target that is going to be tested are named, because a test
          // counts as built only if its validation aspect is among those that succeeded: so
          // the node that names them is evaluated, and finds the answer above.
          tests++;
          admission.shareProbe(
              CompletedTopLevelKey.of(target, context, /* willTest= */ true),
              found(CompletedTopLevelValue.tested(key, built.servedTests().get(index))));
          continue;
        }
        answered.add(target);
        admission.shareProbe(
            CompletedTopLevelKey.of(target, context, /* willTest= */ false),
            found(CompletedTopLevelValue.builtWith(key)));
        if (aspectsKey != null) {
          admission.noteAspectsOfATargetBuilt(aspectsKey);
        }
      }
    }
    ImmutableSet<ConfiguredTargetKey> built = answered.build();
    admission.noteBuiltByPackage(lookups.size(), valid, built.size() + tests);
    applying.close();
    return built;
  }

  private static CompletedProbe found(CompletedTopLevelValue value) {
    return new CompletedProbe(new RetrievedValue(value), ImmutableMap.of());
  }

  private BuiltPackages() {}
}
