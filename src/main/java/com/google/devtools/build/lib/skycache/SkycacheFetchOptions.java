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

import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;

/** How entries are read from the analysis cache service. */
@OptionsClass
public abstract class SkycacheFetchOptions extends OptionsBase {

  @Option(
      name = "canva_skycache_fetch_closures",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "Read from --experimental_remote_analysis_cache over one stream, on which the service"
              + " sends the entries a requested entry references along with it, instead of in"
              + " batches of keys. Bazel learns which nested values an entry needs only by"
              + " deserializing it, so in batches every level of nesting is another request."
              + " Falls back to batches against a service that cannot follow references.")
  public abstract boolean getFetchClosures();

  @Option(
      name = "canva_skycache_execution_values",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "Also write action results to the analysis cache and read them back, so that an action"
              + " whose owner's declared sources and analysis inputs are unchanged is neither"
              + " run nor looked up in the action cache. Experimental.")
  public abstract boolean getExecutionValues();

  @Option(
      name = "canva_skycache_local_action_results",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_execution_values, also write the results of actions whose outputs"
              + " exist only in the writer's output base, such as file writes. A reader is told"
              + " those outputs are remote, and runs the action only if something reads one.")
  public abstract boolean getLocalActionResults();

  @Option(
      name = "canva_skycache_test_results",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_execution_values, also write the results of tests that passed."
              + " A reader reports such a test as a cached pass when the declared sources and"
              + " analysis inputs of its target are unchanged, without building the test's"
              + " inputs or asking the remote cache. Experimental.")
  public abstract boolean getTestResults();

  @Option(
      name = "canva_skycache_completions",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_execution_values, also record each top-level target and aspect"
              + " that was built completely. A reader reports one as built when the declared"
              + " sources and analysis inputs below it are unchanged, without asking for any of"
              + " its artifacts: its build event lists no output files and none of its outputs"
              + " are downloaded. For builds that check that everything builds and passes."
              + " Experimental.")
  public abstract boolean getCompletions();

  @Option(
      name = "canva_skycache_top_level_window",
      defaultValue = "8192",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_completions, a reader asks whether each top-level target and"
              + " aspect was built before it asks for the target or aspect itself, and lets at"
              + " most this many of those that were built fetch their values at a time. The rest"
              + " wait, out of the way of the targets that have something to build, which then"
              + " start building within seconds and not once the rest of the build has been"
              + " read. Zero asks for everything at once, in the order of the build."
              + " Experimental.")
  public abstract int getTopLevelWindow();

  @Option(
      name = "canva_skycache_built_without_values",
      defaultValue = "false",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_completions and a --canva_skycache_top_level_window, a reader"
              + " reports a top-level target that is recorded as built, and is not going to be"
              + " tested, without fetching the target, and a top-level aspect likewise. Their"
              + " providers and actions are most of what a reader of a large build fetches, and"
              + " nothing reads them in a build that only reports what builds. The targets the"
              + " build result holds then provide nothing. Experimental.")
  public abstract boolean getBuiltWithoutValues();

  @Option(
      name = "canva_skycache_built_by_package",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_built_without_values, a reader first asks, once for each package"
              + " of its top-level targets, which of them are recorded as built with all their"
              + " aspects. A package nothing has changed under answers for every target in it,"
              + " which is then not asked about. Experimental.")
  public abstract boolean getBuiltByPackage();

  @Option(
      name = "canva_skycache_served_tests",
      defaultValue = "true",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "With --canva_skycache_built_without_values, a reader reports a test whose results are"
              + " in the record of its target without fetching the target, which is otherwise"
              + " fetched whole to report results the cache already holds. Experimental.")
  public abstract boolean getServedTests();
}
