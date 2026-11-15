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

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.FilesToRunProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.analysis.configuredtargets.AbstractConfiguredTarget;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Info;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Provider;
import javax.annotation.Nullable;

/**
 * Stands in for a top-level target that the remote analysis cache records as built, in a build
 * that only wants to know that everything builds.
 *
 * <p>Such a build reports the target as built without asking for any of its artifacts, and then
 * nothing reads what the target provides. Its value is still most of what a reader of a large
 * build fetches and deserializes: every provider and every action of every top-level target. This
 * is the target without them. It knows its label and its configuration, which is what the events
 * of a build are made from, and provides nothing.
 *
 * <p>It is never the value of the target's node. A target that depends on this one asks for that
 * node and is given the target itself.
 */
final class BuiltTopLevelTargetValue implements ConfiguredTargetValue {
  private final BuiltTarget target;

  /**
   * @param builtWith the key the target was built with, which is the key of its node
   */
  BuiltTopLevelTargetValue(ActionLookupKey builtWith) {
    this.target = new BuiltTarget(builtWith);
  }

  @Override
  public ConfiguredTarget getConfiguredTarget() {
    return target;
  }

  @Override
  public void clear(boolean clearEverything) {}

  @Nullable
  @Override
  public NestedSet<Package.Metadata> getTransitivePackages() {
    return null;
  }

  @Override
  public String toString() {
    return "BuiltTopLevelTargetValue(" + target.getLookupKey() + ")";
  }

  private static final class BuiltTarget extends AbstractConfiguredTarget {
    private BuiltTarget(ActionLookupKey builtWith) {
      super(builtWith, NestedSetBuilder.emptySet(Order.STABLE_ORDER));
    }

    @Override
    public boolean isRuleConfiguredTarget() {
      return true;
    }

    /**
     * Every target has files to build and files to run, and what prints the result of a build
     * reads them without asking whether they are there. These are empty: the build did not ask
     * for the target's files, and has nothing to print for it.
     */
    @Nullable
    @Override
    public <P extends TransitiveInfoProvider> P getProvider(Class<P> provider) {
      if (provider == FileProvider.class) {
        return provider.cast(FileProvider.EMPTY);
      }
      if (provider == FilesToRunProvider.class) {
        return provider.cast(FilesToRunProvider.EMPTY);
      }
      return super.getProvider(provider);
    }

    @Override
    public boolean isCreatedInSymbolicMacro() {
      // Asked of a dependency when its visibility is checked, and nothing depends on this.
      return false;
    }

    @Nullable
    @Override
    protected Info rawGetStarlarkProvider(Provider.Key providerKey) {
      return null;
    }

    @Nullable
    @Override
    protected Object rawGetStarlarkProvider(String providerKey) {
      return null;
    }
  }
}
