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

import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import javax.annotation.Nullable;

/**
 * What the remote analysis cache holds under a {@link CompletedTopLevelKey}. Being there is most
 * of what it says: the entry's file dependencies carry the meaning.
 *
 * @param formatVersion to be raised if what an entry promises ever changes
 * @param builtWith for a rule's target that was built and not tested, the key it was built with,
 *     which is the key of its node: enough for a reader to report it as built without fetching
 *     the target. Null for anything else, which a reader fetches.
 */
@AutoCodec
public record CompletedTopLevelValue(int formatVersion, @Nullable ActionLookupKey builtWith)
    implements DeserializedSkyValue {
  public static final CompletedTopLevelValue INSTANCE = new CompletedTopLevelValue(1, null);

  public static CompletedTopLevelValue builtWith(ActionLookupKey key) {
    return new CompletedTopLevelValue(1, key);
  }
}
