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
package com.google.devtools.build.lib.skyframe.serialization.analysis;

import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * What the remote analysis cache holds under a {@link CompletedTopLevelKey}. It says nothing
 * beyond being there: the entry's file dependencies carry the meaning.
 *
 * @param formatVersion to be raised if what an entry promises ever changes
 */
@AutoCodec
public record CompletedTopLevelValue(int formatVersion) implements DeserializedSkyValue {
  public static final CompletedTopLevelValue INSTANCE = new CompletedTopLevelValue(1);
}
