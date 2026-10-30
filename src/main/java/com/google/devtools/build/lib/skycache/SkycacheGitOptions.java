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

/** How the analysis cache versions the workspace from its git history. */
@OptionsClass
public abstract class SkycacheGitOptions extends OptionsBase {

  @Option(
      name = "canva_skycache_git",
      defaultValue = "/usr/bin/git",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "The git binary that versions the workspace for the remote analysis cache. It runs with"
              + " a fixed environment. Empty disables versioning, which serves every cached entry"
              + " as valid whatever changed since it was written: only for measurements.")
  public abstract String getGit();

  @Option(
      name = "canva_skycache_mainline",
      defaultValue = "origin/master",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "The branch whose first-parent history numbers versions for the remote analysis cache."
              + " Every entry is keyed at a commit on it; an upload from a working tree that"
              + " differs from that commit leaves out the entries the differences reach.")
  public abstract String getMainline();

  @Option(
      name = "canva_skycache_base",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "The mainline commit the analysis cache is keyed at: a download reads its upload,"
              + " checking every entry against what differs between that commit and the working"
              + " tree, and an upload writes it, leaving out whatever those differences reach."
              + " Defaults to the merge base of HEAD and --canva_skycache_mainline.")
  public abstract String getBase();
}
