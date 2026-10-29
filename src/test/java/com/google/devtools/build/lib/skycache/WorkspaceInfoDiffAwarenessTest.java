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

import static com.google.common.truth.Truth.assertThat;

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories;
import com.google.devtools.build.lib.skyframe.DiffAwareness;
import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.ModifiedFileSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.common.options.OptionsProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link WorkspaceInfoDiffAwareness}. */
@RunWith(JUnit4.class)
public final class WorkspaceInfoDiffAwarenessTest {

  /** Numbers its views and reports which pair each diff was asked about. */
  private static final class FakeDiffAwareness implements DiffAwareness {
    private record FakeView(int number) implements View {}

    private int views;
    private final List<String> diffs = new ArrayList<>();
    private boolean closed;

    @Override
    public View getCurrentView(OptionsProvider options) {
      return new FakeView(++views);
    }

    @Override
    public ModifiedFileSet getDiff(@Nullable View oldView, View newView) {
      diffs.add(oldView + "->" + newView);
      return ModifiedFileSet.builder().modify(PathFragment.create("changed")).build();
    }

    @Override
    public String name() {
      return "fake";
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final WorkspaceInfoFromDiff INFO = new WorkspaceInfoFromDiff() {};

  @Test
  public void attachesInfoAndDelegatesEverythingElse() throws Exception {
    var fake = new FakeDiffAwareness();
    var info = new AtomicReference<WorkspaceInfoFromDiff>();
    DiffAwareness diffAwareness =
        new WorkspaceInfoDiffAwareness.Factory((root, ignored, options) -> fake, info::get)
            .maybeCreate(
                Root.fromPath(new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/ws")),
                IgnoredSubdirectories.EMPTY,
                /* optionsProvider= */ null);

    DiffAwareness.View first = diffAwareness.getCurrentView(/* options= */ null);
    info.set(INFO);
    DiffAwareness.View second = diffAwareness.getCurrentView(/* options= */ null);

    assertThat(first.getWorkspaceInfo()).isNull();
    assertThat(second.getWorkspaceInfo()).isSameInstanceAs(INFO);
    assertThat(diffAwareness.getDiff(first, second).modifiedSourceFiles())
        .containsExactly(PathFragment.create("changed"));
    assertThat(diffAwareness.getDiff(null, first)).isNotNull();
    // The delegate sees its own views, unwrapped.
    assertThat(fake.diffs)
        .containsExactly("FakeView[number=1]->FakeView[number=2]", "null->FakeView[number=1]")
        .inOrder();
    assertThat(diffAwareness.name()).isEqualTo("fake");
    diffAwareness.close();
    assertThat(fake.closed).isTrue();
  }

  @Test
  public void declinesWhatTheDelegateDeclines() {
    assertThat(
            new WorkspaceInfoDiffAwareness.Factory((root, ignored, options) -> null, () -> INFO)
                .maybeCreate(
                    Root.fromPath(
                        new InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/ws")),
                    IgnoredSubdirectories.EMPTY,
                    /* optionsProvider= */ null))
        .isNull();
  }
}
