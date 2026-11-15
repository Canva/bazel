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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.skyframe.serialization.DeserializedSkyValue;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;

/**
 * What the remote analysis cache holds under a {@link CompletedPackageKey}: the targets of the
 * package that were built and not tested, each with all its top-level aspects.
 *
 * @param formatVersion to be raised if what an entry promises ever changes
 * @param requested the key each target was asked for with, which is the key a reader has
 * @param builtWith the key each was built with, in the same order: see {@link
 *     CompletedTopLevelValue#builtWith}
 */
@AutoCodec
public record CompletedPackageValue(
    int formatVersion,
    ImmutableList<ActionLookupKey> requested,
    ImmutableList<ActionLookupKey> builtWith)
    implements DeserializedSkyValue {
  public static CompletedPackageValue of(
      ImmutableList<ActionLookupKey> requested, ImmutableList<ActionLookupKey> builtWith) {
    return new CompletedPackageValue(1, requested, builtWith);
  }
}
