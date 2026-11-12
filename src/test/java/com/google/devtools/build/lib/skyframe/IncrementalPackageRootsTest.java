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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.PackageRoots.PackageRootLookup;
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.cmdline.PackageIdentifier;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import javax.annotation.Nullable;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for the package root lookup of {@link IncrementalPackageRoots}. */
@RunWith(JUnit4.class)
public final class IncrementalPackageRootsTest {
  private final FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
  private final Path outputBase = fs.getPath("/output_base");
  private final Path externalDirectory = outputBase.getRelative("external");
  private final Root workspace = Root.fromPath(fs.getPath("/workspace"));

  private static final PackageIdentifier EXTERNAL_PACKAGE =
      PackageIdentifier.create(
          RepositoryName.createUnvalidated("sysroot+"), PathFragment.create("usr/include"));

  @After
  public void clearStaticState() {
    IncrementalPackageRoots.setUnloadedExternalPackagesDirectory(null);
  }

  private PackageRootLookup lookup(@Nullable PackageRootLookup loadedPackages) {
    return IncrementalPackageRoots.createAndRegisterToEventBus(
            outputBase.getRelative("execroot/_main"),
            workspace,
            new EventBus(),
            "bazel-",
            IgnoredSubdirectories.EMPTY,
            /* allowExternalRepositories= */ true,
            loadedPackages)
        .getPackageRootLookup();
  }

  @Test
  public void unloadedExternalPackage_hasNoRoot() {
    assertThat(lookup(packageId -> null).getRootForPackage(EXTERNAL_PACKAGE)).isNull();
  }

  @Test
  public void unloadedExternalPackage_isRootedAtItsRepositoryOnceTheDirectoryIsKnown() {
    IncrementalPackageRoots.setUnloadedExternalPackagesDirectory(externalDirectory);

    assertThat(lookup(packageId -> null).getRootForPackage(EXTERNAL_PACKAGE))
        .isEqualTo(Root.fromPath(externalDirectory.getRelative("sysroot+")));
    assertThat(lookup(null).getRootForPackage(EXTERNAL_PACKAGE))
        .isEqualTo(Root.fromPath(externalDirectory.getRelative("sysroot+")));
  }

  @Test
  public void loadedExternalPackage_keepsTheRootItWasLoadedFrom() {
    Root loadedFrom = Root.fromPath(fs.getPath("/somewhere/else"));
    IncrementalPackageRoots.setUnloadedExternalPackagesDirectory(externalDirectory);

    assertThat(lookup(packageId -> loadedFrom).getRootForPackage(EXTERNAL_PACKAGE))
        .isEqualTo(loadedFrom);
  }

  @Test
  public void mainRepositoryPackage_keepsTheSourceRoot() {
    IncrementalPackageRoots.setUnloadedExternalPackagesDirectory(externalDirectory);

    assertThat(
            lookup(packageId -> null)
                .getRootForPackage(PackageIdentifier.createInMainRepo("tools/build")))
        .isEqualTo(workspace);
  }
}
