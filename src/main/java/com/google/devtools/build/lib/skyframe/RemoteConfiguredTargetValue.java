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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.ActionLookupValue;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.packages.TargetData;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.errorprone.annotations.Keep;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * A {@link ConfiguredTargetValue} fetched from a remote source.
 *
 * <p>This doesn't contain actions, but contains enough information for dependents to perform
 * analysis. In particular, contains {@link TargetData}, allowing the construction of {@link
 * ConfiguredTargetAndData}, containing everything needed by dependents of the {@link
 * ConfiguredTargetValue} in analysis.
 */
public sealed class RemoteConfiguredTargetValue
    implements ConfiguredTargetValue, DeserializedSkyValue {

  @Nullable // Null after clearing.
  private ConfiguredTarget configuredTarget;

  @Nullable // Null after clearing.
  private TargetData targetData;

  // Null after clearing, and if the writer did not track transitive packages.
  @Nullable private NestedSet<Package.Metadata> transitiveRepositoryPackages;

  private RemoteConfiguredTargetValue(
      ConfiguredTarget configuredTarget,
      TargetData targetData,
      @Nullable NestedSet<Package.Metadata> transitiveRepositoryPackages) {
    this.configuredTarget = configuredTarget;
    this.targetData = targetData;
    this.transitiveRepositoryPackages = transitiveRepositoryPackages;
  }

  @Nullable // Null after clearing everything.
  @Override
  public final ConfiguredTarget getConfiguredTarget() {
    return configuredTarget;
  }

  @Nullable // Never serialized.
  @Override
  public final NestedSet<Package.Metadata> getTransitivePackages() {
    return null;
  }

  @Nullable
  @Override
  public final NestedSet<Package.Metadata> getTransitiveRepositoryPackages() {
    return transitiveRepositoryPackages;
  }

  @Override
  public final void clear(boolean clearEverything) {
    if (clearEverything) {
      configuredTarget = null;
      targetData = null;
    }
    transitiveRepositoryPackages = null;
  }

  @Override
  @Nullable // Null after clearing everything.
  public final TargetData getTargetData() {
    return targetData;
  }

  @Override
  public final boolean isCleared() {
    return configuredTarget == null;
  }

  @Override
  public final String toString() {
    return toStringHelper(this)
        .add("configuredTarget", configuredTarget)
        .add("targetData", targetData)
        .toString();
  }

  private static final class RemoteRuleConfiguredTargetValue extends RemoteConfiguredTargetValue
      implements ActionLookupValue {
    private final ImmutableList<ActionAnalysisMetadata> actions;

    RemoteRuleConfiguredTargetValue(
        RuleConfiguredTarget ruleConfiguredTarget,
        TargetData targetData,
        @Nullable NestedSet<Package.Metadata> transitiveRepositoryPackages) {
      super(ruleConfiguredTarget, targetData, transitiveRepositoryPackages);
      this.actions = ruleConfiguredTarget.getActions();
    }

    @Override
    public ImmutableList<ActionAnalysisMetadata> getActions() {
      return actions;
    }
  }

  public static ConfiguredTargetValueCodec codec() {
    return ConfiguredTargetValueCodec.INSTANCE;
  }

  /**
   * Forgets what serializing values has memoized. To be called once a command that serialized
   * values is over, so that the next one does not keep this one's packages alive.
   */
  public static void clearSerializationMemos() {
    RepositoryPackages.clear();
  }

  /**
   * Reduces the transitive packages of a value to one package for each repository among them: see
   * {@link ConfiguredTargetValue#getTransitiveRepositoryPackages}.
   *
   * <p>The transitive packages of the values of a build are nested sets that share most of their
   * nodes, one node for a value, so the reduction of a node is computed once and kept. Values
   * near each other reduce to the same few repositories, and equal results are one object, which
   * is then serialized once.
   */
  private static final class RepositoryPackages {
    private static final ConcurrentHashMap<NestedSet.Node, ImmutableList<Package.Metadata>> byNode =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<
            ImmutableList<Package.Metadata>, ImmutableList<Package.Metadata>>
        canonical = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<
            ImmutableList<Package.Metadata>, NestedSet<Package.Metadata>>
        sets = new ConcurrentHashMap<>();

    static void clear() {
      byNode.clear();
      canonical.clear();
      sets.clear();
    }

    static NestedSet<Package.Metadata> of(NestedSet<Package.Metadata> transitivePackages) {
      return sets.computeIfAbsent(
          reduce(transitivePackages), list -> NestedSetBuilder.wrap(Order.STABLE_ORDER, list));
    }

    private static ImmutableList<Package.Metadata> reduce(NestedSet<Package.Metadata> root) {
      // Children before parents, without recursion: these sets are as deep as the build graph.
      var pending = new ArrayDeque<NestedSet<Package.Metadata>>();
      pending.push(root);
      while (!pending.isEmpty()) {
        NestedSet<Package.Metadata> set = pending.peek();
        NestedSet.Node node = set.toNode();
        if (byNode.containsKey(node)) {
          pending.pop();
          continue;
        }
        ImmutableList<NestedSet<Package.Metadata>> children = set.getNonLeaves();
        boolean childrenDone = true;
        for (NestedSet<Package.Metadata> child : children) {
          if (!byNode.containsKey(child.toNode())) {
            pending.push(child);
            childrenDone = false;
          }
        }
        if (!childrenDone) {
          continue;
        }
        // Keyed by repository name, so that equal sets of repositories give equal lists.
        var byRepository = new TreeMap<String, Package.Metadata>();
        for (Package.Metadata pkg : set.getLeaves()) {
          add(byRepository, pkg);
        }
        // Most children reduce to the same list as one of their siblings.
        var merged = Sets.<ImmutableList<Package.Metadata>>newIdentityHashSet();
        for (NestedSet<Package.Metadata> child : children) {
          ImmutableList<Package.Metadata> reduced = byNode.get(child.toNode());
          if (merged.add(reduced)) {
            for (Package.Metadata pkg : reduced) {
              add(byRepository, pkg);
            }
          }
        }
        ImmutableList<Package.Metadata> result = ImmutableList.copyOf(byRepository.values());
        ImmutableList<Package.Metadata> existing = canonical.putIfAbsent(result, result);
        byNode.put(node, existing != null ? existing : result);
        pending.pop();
      }
      return byNode.get(root.toNode());
    }

    /** Keeps, of the packages of a repository, the one whose name sorts first. */
    private static void add(TreeMap<String, Package.Metadata> byRepository, Package.Metadata pkg) {
      byRepository.merge(
          pkg.packageIdentifier().getRepository().getName(),
          pkg,
          (a, b) -> a.packageIdentifier().compareTo(b.packageIdentifier()) <= 0 ? a : b);
    }
  }

  /**
   * Codec for {@link ConfiguredTargetValue}s.
   *
   * <p>This codec is crafted to serialize the minimal amount of data needed by its rdeps.
   *
   * <p>The serialized constituents are: the {@link ConfiguredTarget}, followed by its (compact)
   * {@link TargetData}, if it already exists. Otherwise, the {@link TargetData} will be constructed
   * from the {@link Target} in the {@link Package} dep.
   */
  @Keep // Accessed reflectively.
  private static class ConfiguredTargetValueCodec
      extends DeferredObjectCodec<ConfiguredTargetValue> {

    private static final ConfiguredTargetValueCodec INSTANCE = new ConfiguredTargetValueCodec();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<ConfiguredTargetValue> getEncodedClass() {
      return ConfiguredTargetValue.class;
    }

    @Override
    public ImmutableSet<Class<? extends ConfiguredTargetValue>> additionalEncodedClasses() {
      return ImmutableSet.of(
          RuleConfiguredTargetValue.class,
          NonRuleConfiguredTargetValue.class,
          RemoteConfiguredTargetValue.class);
    }

    @Override
    public void serialize(
        SerializationContext context, ConfiguredTargetValue obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      ConfiguredTarget configuredTarget =
          checkNotNull(
              obj.getConfiguredTarget(),
              "tried to serialize a cleared ConfiguredTargetValue? %s",
              obj);
      context.serialize(configuredTarget, codedOut);
      NestedSet<Package.Metadata> transitivePackages = obj.getTransitivePackages();
      context.serialize(
          transitivePackages != null
              ? RepositoryPackages.of(transitivePackages)
              // Null unless this value was itself read from the cache.
              : obj.getTransitiveRepositoryPackages(),
          codedOut);
      TargetData targetData = obj.getTargetData();
      if (targetData != null) {
        context.serialize(targetData, codedOut);
        return;
      }

      // Looks up the Target and serializes it as TargetData.
      Label label = configuredTarget.getLabel();
      var pkgFunction = context.getDependency(PrerequisitePackageFunction.class);
      Package pkg;
      try {
        pkg = pkgFunction.getExistingPackage(label.getPackageIdentifier());
      } catch (InterruptedException e) {
        throw new SerializationException(
            "serialization of ConfiguredTargetValue "
                + configuredTarget.getLabel()
                + " interrupted while looking up its package",
            e);
      }

      Target target;
      try {
        target = pkg.getTarget(label.getName());
      } catch (NoSuchTargetException e) {
        throw new IllegalStateException(
            "The target associated with " + configuredTarget + " was unexpectedly missing", e);
      }
      context.serialize(target.reduceForSerialization(), codedOut);
    }

    @Override
    public DeferredValue<RemoteConfiguredTargetValue> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      var value = new DeserializationBuilder();
      context.deserialize(codedIn, value, DeserializationBuilder::setConfiguredTarget);
      context.deserialize(
          codedIn, value, DeserializationBuilder::setTransitiveRepositoryPackages);
      context.deserialize(codedIn, value, DeserializationBuilder::setTargetData);
      return value;
    }

    private static class DeserializationBuilder
        implements DeferredValue<RemoteConfiguredTargetValue> {
      private ConfiguredTarget configuredTarget;
      private TargetData targetData;
      @Nullable private NestedSet<Package.Metadata> transitiveRepositoryPackages;

      @Override
      public RemoteConfiguredTargetValue call() {
        checkNotNull(configuredTarget);
        checkNotNull(targetData);
        return configuredTarget instanceof RuleConfiguredTarget ruleConfiguredTarget
            ? new RemoteRuleConfiguredTargetValue(
                ruleConfiguredTarget, targetData, transitiveRepositoryPackages)
            : new RemoteConfiguredTargetValue(
                configuredTarget, targetData, transitiveRepositoryPackages);
      }

      @SuppressWarnings("unchecked")
      private static void setTransitiveRepositoryPackages(
          DeserializationBuilder builder, Object value) {
        builder.transitiveRepositoryPackages = (NestedSet<Package.Metadata>) value;
      }

      private static void setConfiguredTarget(DeserializationBuilder builder, Object value) {
        builder.configuredTarget = (ConfiguredTarget) value;
      }

      private static void setTargetData(DeserializationBuilder builder, Object value) {
        builder.targetData = (TargetData) value;
      }
    }
  }
}
