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

import com.google.devtools.common.options.Converters.ByteSizeConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;

/** Where the analysis cache keeps entries on this machine, shared by every workspace on it. */
@OptionsClass
public abstract class SkycacheLocalOptions extends OptionsBase {

  @Option(
      name = "canva_skycache_local_path",
      defaultValue = "",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "An absolute directory, outside the workspace, holding an analysis cache on local disk."
              + " Readable and writable by every Bazel server of the user. Used alone, it is the"
              + " cache; with --experimental_remote_analysis_cache, it is read first, keeps what"
              + " the service answers, and takes every write.")
  public abstract String getLocalPath();

  @Option(
      name = "canva_skycache_local_max_size",
      defaultValue = "20G",
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      converter = ByteSizeConverter.class,
      help =
          "The size above which the oldest entries of --canva_skycache_local_path are removed,"
              + " checked as each command finishes writing.")
  public abstract long getLocalMaxSize();
}
