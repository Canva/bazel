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
import java.util.List;

/** Other analysis cache services an upload is copied to. */
@OptionsClass
public abstract class SkycacheMirrorOptions extends OptionsBase {

  @Option(
      name = "canva_skycache_mirror",
      defaultValue = "null",
      allowMultiple = true,
      documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
      effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
      help =
          "An analysis cache service, such as the one in another region, that every upload to"
              + " --experimental_remote_analysis_cache is also written to, with the same"
              + " credentials and TLS options. Downloads never read it. A mirror that fails a write"
              + " fails neither the build nor the other copies; it only goes without the snapshot"
              + " record, so its readers analyse locally rather than miss entry by entry. Unused"
              + " with --canva_skycache_local_path, which takes every write.")
  public abstract List<String> getMirrors();
}
