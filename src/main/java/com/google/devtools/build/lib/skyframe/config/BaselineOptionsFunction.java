// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ExecutionTransitionFactory;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader;
import com.google.devtools.build.lib.analysis.config.StarlarkExecTransitionLoader.StarlarkExecTransitionLoadingException;
import com.google.devtools.build.lib.analysis.config.transitions.BaselineOptionsValue;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionUtil;
import com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducer;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.Driver;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import java.util.Optional;
import javax.annotation.Nullable;

/** A builder for {@link BaselineOptionsValue} instances. */
public final class BaselineOptionsFunction implements SkyFunction {
  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws InterruptedException, BaselineOptionsFunctionException {
    BaselineOptionsValue.Key key = (BaselineOptionsValue.Key) skyKey.argument();

    BuildOptions rawBaselineOptions = PrecomputedValue.BASELINE_CONFIGURATION.get(env);

    // Some test infrastructure only creates mock or partial top-level BuildOptions such that
    // PlatformOptions or even CoreOptions might not be included.
    // In that case, is not worth doing any special processing of the baseline.
    if (rawBaselineOptions.hasNoConfig()) {
      return BaselineOptionsValue.create(rawBaselineOptions);
    }

    // First, make sure platform_mappings applied to the top-level baseline option.
    BuildOptions mappedBaselineOptions = mapBuildOptions(env, rawBaselineOptions);
    if (mappedBaselineOptions == null) {
      return null;
    }

    Optional<StarlarkAttributeTransitionProvider> starlarkExecTransition;
    try {
      starlarkExecTransition =
          StarlarkExecTransitionLoader.loadStarlarkExecTransition(
              mappedBaselineOptions,
              (bzlKey) -> (BzlLoadValue) env.getValueOrThrow(bzlKey, BzlLoadFailedException.class));
    } catch (StarlarkExecTransitionLoadingException e) {
      throw new BaselineOptionsFunctionException(e);
    }
    if (starlarkExecTransition == null) {
      return null;
    }

    // Next, apply elements of BaselineOptionsKey: apply exec transition and/or adjust platform
    BuildOptions adjustedBaselineOptions = mappedBaselineOptions;
    if (key.afterExecTransition()) {
      // A null executionPlatform actually skips transition application so need some value here when
      // not overriding the platform. It is safe to supply some fake value here (as long as it is
      // constant) since the baseline should never be used to actually construct an action or do
      // toolchain resolution.
      PatchTransition execTransition =
          ExecutionTransitionFactory.createFactory()
              .create(
                  AttributeTransitionData.builder()
                      .executionPlatform(
                          key.newPlatform() != null
                              ? key.newPlatform()
                              : Label.parseCanonicalUnchecked(
                                  "//this_is_a_faked_exec_platform_for_blaze_internals"))
                      .analysisData(starlarkExecTransition.orElse(null))
                      .build());
      adjustedBaselineOptions =
          execTransition.patch(
              TransitionUtil.restrict(execTransition, adjustedBaselineOptions), env.getListener());
    } else if (key.newPlatform() != null) {
      // Clone for safety as-is the standard for all transitions.
      adjustedBaselineOptions = adjustedBaselineOptions.clone();
      adjustedBaselineOptions.get(PlatformOptions.class).platforms =
          ImmutableList.of(key.newPlatform());
    }

    // Re-apply platform_mappings if we updated the platform.
    // This initially seems somewhat redundant with the application above; however, this is meant to
    // better track how the top-level build options will initially have platform mappings applied
    // before some transition (e.g exec transition) changes the platform to cause another
    // application of platform mappings. Platforms in platform_mappings may change different sets of
    // options so applying both should lead to better baselines.
    // TODO(twigg,jcater): Evaluate and reconsider this 'scenario'.
    BuildOptions remappedAdjustedBaselineOptions = adjustedBaselineOptions;
    if (key.newPlatform() != null) {
      remappedAdjustedBaselineOptions = mapBuildOptions(env, remappedAdjustedBaselineOptions);
      if (remappedAdjustedBaselineOptions == null) {
        return null;
      }
    }

    return BaselineOptionsValue.create(remappedAdjustedBaselineOptions);
  }

  @Nullable
  private static BuildOptions mapBuildOptions(Environment env, BuildOptions rawBaselineOptions)
      throws InterruptedException, BaselineOptionsFunctionException {
    BuildOptionsMapper mapper = new BuildOptionsMapper(rawBaselineOptions);

    try {
      BuildConfigurationKey key = mapper.drive(env);
      if (key == null) {
        return null;
      }
      return key.getOptions();
    } catch (PlatformMappingException e) {
      throw new BaselineOptionsFunctionException(e);
    } catch (OptionsParsingException e) {
      throw new BaselineOptionsFunctionException(e);
    }
  }

  /** Uses BuildConfigurationKeyProducer to handle finalizing the options. */
  private static class BuildOptionsMapper implements BuildConfigurationKeyProducer.ResultSink {
    private static final String TRANSITION_KEY = "key";

    private final Driver driver;
    private ImmutableMap<String, BuildConfigurationKey> transitionedOptions;
    private OptionsParsingException transitionError;
    private PlatformMappingException platformMappingException;

    private BuildOptionsMapper(BuildOptions options) {
      this.driver =
          new Driver(
              new BuildConfigurationKeyProducer(
                  this, StateMachine.DONE, ImmutableMap.of(TRANSITION_KEY, options)));
    }

    @Override
    public void acceptTransitionError(OptionsParsingException e) {
      this.transitionError = e;
    }

    @Override
    public void acceptPlatformMappingError(PlatformMappingException e) {
      this.platformMappingException = e;
    }

    @Override
    public void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionedOptions) {
      this.transitionedOptions = transitionedOptions;
    }

    @Nullable
    private BuildConfigurationKey drive(LookupEnvironment env)
        throws OptionsParsingException, InterruptedException, PlatformMappingException {
      if (!this.driver.drive(env)) {
        return null;
      }

      if (this.transitionError != null) {
        throw this.transitionError;
      }
      if (this.platformMappingException != null) {
        throw this.platformMappingException;
      }

      return this.transitionedOptions.get(TRANSITION_KEY);
    }
  }

  private static final class BaselineOptionsFunctionException extends SkyFunctionException {
    BaselineOptionsFunctionException(Exception e) {
      super(e, Transience.PERSISTENT);
    }
  }
}
