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
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skycache.GitVersions.History;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * Answers versions from the first-parent history of a clean mainline checkout.
 *
 * <p>A path's version is the ordinal of the last commit in the window that touched it, or the
 * window start if none did: either way the path is unchanged from that commit to the base. Versions
 * name the state a record was written from rather than when it was read, so they only have to be
 * canonical, and an over-approximation like the window start costs sharing between bases, not
 * correctness.
 *
 * <p>The writer is checked clean before this is used, so no path can have a version past the base.
 */
final class GitLongVersionGetter implements LongVersionGetter {

  private final ImmutableList<Path> workspaceRoots;
  private final ListenableFuture<History> history;

  /**
   * @param workspace the root of the working tree, as the build sees it
   * @param history read in the background, since the serializer asks only after analysis
   */
  GitLongVersionGetter(Path workspace, ListenableFuture<History> history) {
    ImmutableList.Builder<Path> roots = ImmutableList.<Path>builder().add(workspace);
    try {
      // The serializer hands over resolved paths.
      Path resolved = workspace.resolveSymbolicLinks();
      if (!resolved.equals(workspace)) {
        roots.add(resolved);
      }
    } catch (IOException e) {
      // Paths under the unresolved workspace still match.
    }
    this.workspaceRoots = roots.build();
    this.history = history;
  }

  @Override
  public long getFilePathOrSymlinkVersion(Path path) throws IOException {
    String relative = workspaceRelative(path);
    if (relative == null) {
      return MINIMAL;
    }
    History history = history();
    return history.fileVersions().getOrDefault(relative, history.windowStart());
  }

  @Override
  public long getDirectoryListingVersion(Path path) throws IOException {
    String relative = workspaceRelative(path);
    if (relative == null) {
      return MINIMAL;
    }
    History history = history();
    return history.listingVersions().getOrDefault(relative, history.windowStart());
  }

  @Override
  public long getNonexistentPathVersion(Path path) throws IOException {
    String relative = workspaceRelative(path);
    if (relative == null) {
      return MINIMAL;
    }
    // Deleted in the window, or never existed as far as can be told.
    return history().fileVersions().getOrDefault(relative, (int) MINIMAL);
  }

  /**
   * Returns the path relative to the working tree, or null outside it.
   *
   * <p>Outside covers {@code external/}, which the serializer versions itself, and absolute paths a
   * toolchain might read. Neither is in the history.
   */
  @Nullable
  private String workspaceRelative(Path path) {
    for (Path root : workspaceRoots) {
      // By fragment: the serializer's paths need not share the workspace's file system instance.
      if (path.asFragment().startsWith(root.asFragment())) {
        return path.asFragment().relativeTo(root.asFragment()).getPathString();
      }
    }
    return null;
  }

  private History history() throws IOException {
    try {
      return history.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted reading git history", e);
    } catch (ExecutionException e) {
      throw new IOException("reading git history: " + e.getCause().getMessage(), e.getCause());
    }
  }
}
