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

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plants the execroot link to the external repository of each source artifact read from the remote
 * analysis cache.
 *
 * <p>Those links are normally planted from the transitive packages of each top-level target as its
 * analysis completes. A configured target deserialized from the cache has none, and nothing loaded
 * the packages it depends on, so on an output base that never built them an action reading {@code
 * external/<repo>/...} finds no such path. Every source artifact a cached value refers to is
 * rebuilt through the deserialization context, before any action that reads it can run, which makes
 * that the place to plant its repository's link.
 *
 * <p>The link is the one {@code SymlinkForest.plantSingleSymlinkForExternalRepo} plants: from the
 * root's exec path to the repository directory in the output base, whether or not that has been
 * fetched yet.
 */
final class DeserializedSourceRepositoryLinks {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private final Path execRoot;
  private final Set<PathFragment> linked = ConcurrentHashMap.newKeySet();

  DeserializedSourceRepositoryLinks(Path execRoot) {
    this.execRoot = execRoot;
  }

  /**
   * Plants the link for a source artifact at {@code execPath} under {@code root}, if the root is an
   * external repository's and no link exists.
   */
  void ensureLinked(PathFragment execPath, ArtifactRoot root) {
    Path target = root.getRoot().asPath();
    if (!root.isExternal() || target == null || execPath.segmentCount() < 2) {
      return;
    }
    // An external source root's own exec path is empty: the repository's place in the execroot is
    // the start of the artifact's, external/<repo> or, in the sibling layout, ../<repo>.
    PathFragment linkPath = execPath.subFragment(0, 2);
    if (!linkPath.getBaseName().equals(target.getBaseName()) || !linked.add(linkPath)) {
      return;
    }
    Path link = execRoot.getRelative(linkPath);
    try {
      if (isPresent(link)) {
        return; // Planted by analysis, or by an earlier command.
      }
      link.getParentDirectory().createDirectoryAndParents();
      link.createSymbolicLink(target);
    } catch (IOException e) {
      if (!isPresent(link)) { // Not lost to a concurrent planting.
        // An action reading the repository will fail and say where.
        logger.atWarning().withCause(e).log("Failed to link %s to %s", link, target);
      }
    }
  }

  private static boolean isPresent(Path link) {
    try {
      return link.exists(Symlinks.NOFOLLOW);
    } catch (IOException e) {
      return false;
    }
  }
}
