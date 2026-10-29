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

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.runtime.BlazeModule;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.skyframe.FsEventsNativeDepsService;
import com.google.devtools.build.lib.skyframe.LocalDiffAwareness;

/**
 * Registers the diff awareness that carries {@link SkycacheModule}'s workspace version.
 *
 * <p>A module of its own because diff awareness factories are tried in registration order and the
 * first to accept a path entry wins, so this one has to come before {@code
 * BazelDiffAwarenessModule}, while {@link SkycacheModule} has to stay where it is among the
 * modules. It wraps the same factory that module registers, so the one that follows it is never
 * reached and file watching is unchanged.
 */
public final class SkycacheDiffAwarenessModule extends BlazeModule {

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    SkycacheModule skycache = runtime.getBlazeModule(SkycacheModule.class);
    if (skycache == null) {
      return;
    }
    builder.addDiffAwarenessFactory(
        new WorkspaceInfoDiffAwareness.Factory(
            new LocalDiffAwareness.Factory(
                ImmutableList.of(), runtime.getBlazeService(FsEventsNativeDepsService.class)),
            skycache::getWorkspaceInfo));
  }
}
