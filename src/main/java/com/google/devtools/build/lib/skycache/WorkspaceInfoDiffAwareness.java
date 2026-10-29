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

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.skyframe.BrokenDiffAwarenessException;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.IncompatibleViewException;
import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.common.options.OptionsProvider;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Attaches workspace info to the views of another {@link DiffAwareness}, changing nothing else.
 *
 * <p>The view's workspace info is the one route by which an evaluating version reaches the remote
 * analysis cache's keys, and Bazel's only diff awareness reports none. Wrapping it keeps file
 * watching exactly as it was: every diff is the delegate's.
 */
final class WorkspaceInfoDiffAwareness implements DiffAwareness {

  /** Wraps whatever {@code delegate} creates. */
  static final class Factory implements DiffAwareness.Factory {
    private final DiffAwareness.Factory delegate;
    private final Supplier<WorkspaceInfoFromDiff> workspaceInfo;

    /**
     * @param workspaceInfo the info for the current command, or null when there is none
     */
    Factory(DiffAwareness.Factory delegate, Supplier<WorkspaceInfoFromDiff> workspaceInfo) {
      this.delegate = delegate;
      this.workspaceInfo = workspaceInfo;
    }

    @Override
    @Nullable
    public DiffAwareness maybeCreate(
        Root pathEntry, IgnoredSubdirectories ignoredPaths, OptionsProvider optionsProvider) {
      DiffAwareness created = delegate.maybeCreate(pathEntry, ignoredPaths, optionsProvider);
      return created == null ? null : new WorkspaceInfoDiffAwareness(created, workspaceInfo);
    }
  }

  private record InfoView(View delegate, @Nullable WorkspaceInfoFromDiff info) implements View {
    @Override
    @Nullable
    public WorkspaceInfoFromDiff getWorkspaceInfo() {
      return info;
    }
  }

  private final DiffAwareness delegate;
  private final Supplier<WorkspaceInfoFromDiff> workspaceInfo;

  private WorkspaceInfoDiffAwareness(
      DiffAwareness delegate, Supplier<WorkspaceInfoFromDiff> workspaceInfo) {
    this.delegate = delegate;
    this.workspaceInfo = workspaceInfo;
  }

  @Override
  public View getCurrentView(OptionsProvider options) throws BrokenDiffAwarenessException {
    return new InfoView(delegate.getCurrentView(options), workspaceInfo.get());
  }

  @Override
  public ModifiedFileSet getDiff(@Nullable View oldView, View newView)
      throws IncompatibleViewException, InterruptedException, BrokenDiffAwarenessException {
    return delegate.getDiff(unwrap(oldView), unwrap(newView));
  }

  @Nullable
  private static View unwrap(@Nullable View view) {
    return view instanceof InfoView infoView ? infoView.delegate() : view;
  }

  @Override
  public String name() {
    return delegate.name();
  }

  @Override
  public void close() {
    delegate.close();
  }
}
