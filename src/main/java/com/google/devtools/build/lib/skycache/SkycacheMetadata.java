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
package com.google.devtools.build.lib.skycache;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * One command's answers to what the snapshot index is asked: which install, configuration and
 * version an upload was, or a download wants.
 *
 * <p>Filled in by {@code RemoteAnalysisCacheFactory} once the configuration is known, and read
 * after the upload by the metadata writer, or before analysis by the reader's metadata query.
 */
final class SkycacheMetadata implements SkycacheMetadataParams {

  private long evaluatingVersion;
  private String bazelVersion = "";
  private ImmutableList<String> targets = ImmutableList.of();
  private boolean useFakeStampData;
  private ImmutableMap<String, String> userOptions = ImmutableMap.of();
  private ImmutableSet<String> projectSclOptions = ImmutableSet.of();
  private ImmutableSet<String> configurationOptions = ImmutableSet.of();
  private String configurationHash = "";

  @Override
  public void init(
      long clNumber,
      String bazelVersion,
      Collection<String> targets,
      boolean useFakeStampData,
      Map<String, String> userOptions,
      Set<String> projectSclOptions) {
    this.evaluatingVersion = clNumber;
    this.bazelVersion = bazelVersion;
    this.targets = ImmutableList.copyOf(targets);
    this.useFakeStampData = useFakeStampData;
    this.userOptions = ImmutableMap.copyOf(userOptions);
    this.projectSclOptions = ImmutableSet.copyOf(projectSclOptions);
  }

  @Override
  public void setOriginalConfigurationOptions(Set<String> configOptions) {
    this.configurationOptions = ImmutableSet.copyOf(configOptions);
  }

  @Override
  public void setConfigurationHash(String configurationHash) {
    this.configurationHash = configurationHash;
  }

  @Override
  public long getEvaluatingVersion() {
    return evaluatingVersion;
  }

  @Override
  public String getConfigurationHash() {
    return configurationHash;
  }

  @Override
  public String getBazelVersion() {
    return bazelVersion;
  }

  @Override
  public boolean getUseFakeStampData() {
    return useFakeStampData;
  }

  @Override
  public Collection<String> getTargets() {
    return targets;
  }

  /**
   * Returns the flags the user passed that reach the configuration, in canonical {@code
   * --name=value} form, for telling a reader which of its flags differ from a primed upload's.
   */
  @Override
  public Collection<String> getConfigFlags() {
    return userOptions.keySet().stream()
        .filter(
            flag -> {
              String name = optionName(flag);
              return configurationOptions.contains(name) || projectSclOptions.contains(name);
            })
        .sorted()
        .collect(toImmutableList());
  }

  /** {@code --name=value} to {@code name}. */
  private static String optionName(String canonicalForm) {
    String name = canonicalForm.startsWith("--") ? canonicalForm.substring(2) : canonicalForm;
    int equals = name.indexOf('=');
    return equals < 0 ? name : name.substring(0, equals);
  }
}
