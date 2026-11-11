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
}
