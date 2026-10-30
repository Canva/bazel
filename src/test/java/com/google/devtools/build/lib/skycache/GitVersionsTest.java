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
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.skycache.GitLongVersionGetter.Uncommitted;
import com.google.devtools.build.lib.skycache.GitVersions.Base;
import com.google.devtools.build.lib.skycache.GitVersions.History;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link GitVersions} and {@link GitLongVersionGetter} against real repositories. */
@RunWith(JUnit4.class)
public final class GitVersionsTest {

  @Rule public final TemporaryFolder tmp = new TemporaryFolder();

  private java.nio.file.Path root;
  private Git git;

  @Before
  public void createRepository() throws Exception {
    root = tmp.newFolder("repo").toPath().toRealPath();
    git = new Git("/usr/bin/git", root);
    git.run("init", "--quiet", "--initial-branch=main");
  }

  private void write(String path, String content) throws IOException {
    java.nio.file.Path file = root.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private String commit(String message) throws Exception {
    git.run("add", "--all");
    git.run(
        "-c",
        "user.name=test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "--quiet",
        "--allow-empty",
        "--message=" + message);
    return git.line("rev-parse", "HEAD");
  }

  /** Mainline of three commits: a/x, then a/x and b/y, then c/d/new. */
  private String[] linearHistory() throws Exception {
    write("a/x", "1");
    String first = commit("first");
    write("a/x", "2");
    write("b/y", "1");
    String second = commit("second");
    write("c/d/new", "1");
    String third = commit("third");
    return new String[] {first, second, third};
  }

  @Test
  public void checkWorkTreeIsRoot() throws Exception {
    linearHistory();
    GitVersions.checkWorkTreeIsRoot(git);

    IOException e =
        assertThrows(
            IOException.class,
            () -> GitVersions.checkWorkTreeIsRoot(new Git("/usr/bin/git", root.resolve("a"))));
    assertThat(e).hasMessageThat().contains("is not the root of its git repository");
  }

  @Test
  public void resolveBase_ordinalIsFirstParentCount() throws Exception {
    String[] commits = linearHistory();

    assertThat(GitVersions.resolveBase(git, "main", "")).isEqualTo(new Base(commits[2], 3));
    assertThat(GitVersions.resolveBase(git, "main", "HEAD~2")).isEqualTo(new Base(commits[0], 1));
  }

  @Test
  public void resolveBase_branchUsesItsMergeBase() throws Exception {
    String[] commits = linearHistory();
    git.run("checkout", "--quiet", "-b", "feature", commits[1]);
    write("f", "1");
    commit("feature");

    assertThat(GitVersions.resolveBase(git, "main", "")).isEqualTo(new Base(commits[1], 2));
  }

  @Test
  public void resolveBase_rejectsCommitOffTheFirstParentChain() throws Exception {
    String[] commits = linearHistory();
    git.run("checkout", "--quiet", "-b", "feature", commits[1]);
    write("f", "1");
    String feature = commit("feature");
    git.run("checkout", "--quiet", "main");
    git.run(
        "-c",
        "user.name=test",
        "-c",
        "user.email=test@example.com",
        "merge",
        "--quiet",
        "--no-ff",
        "--message=merge",
        "feature");

    // The feature commit is an ancestor of main, and its own first-parent count (3) is the
    // ordinal of a different mainline commit.
    IOException e =
        assertThrows(IOException.class, () -> GitVersions.resolveBase(git, "main", feature));
    assertThat(e).hasMessageThat().contains("is not on the first-parent history of main");
    assertThat(GitVersions.resolveBase(git, "main", ""))
        .isEqualTo(new Base(git.line("rev-parse", "HEAD"), 4));
  }

  @Test
  public void changedPaths_ignoresIgnoredFiles() throws Exception {
    write(".gitignore", "ignored\n");
    commit("ignore");
    write("ignored", "1");

    assertThat(GitVersions.changedPaths(git, GitVersions.resolveBase(git, "main", ""))).isEmpty();
  }

  @Test
  public void changedPaths_cleanAtBaseIsEmpty() throws Exception {
    linearHistory();

    assertThat(GitVersions.changedPaths(git, GitVersions.resolveBase(git, "main", ""))).isEmpty();
  }

  @Test
  public void changedPaths_workingTreeChanges() throws Exception {
    linearHistory();
    Base base = GitVersions.resolveBase(git, "main", "");
    write("a/x", "edited");
    write("e/f/untracked", "1");
    Files.delete(root.resolve("b/y"));
    Files.delete(root.resolve("b"));
    Files.delete(root.resolve("c/d/new"));

    // e and e/f are new directories and b is gone. a exists on both sides, and so does c/d: git
    // does not track it, but the build still sees it, empty.
    assertThat(GitVersions.changedPaths(git, base))
        .containsExactly("a/x", "b/y", "b", "c/d/new", "e/f/untracked", "e/f", "e");
  }

  @Test
  public void changedPaths_readerBehindTheBase() throws Exception {
    String[] commits = linearHistory();
    Base base = GitVersions.resolveBase(git, "main", "");
    git.run("checkout", "--quiet", commits[0]);

    // c/d/new and b/y exist only in the base, and a/x differs. Ancestors c, c/d and b are absent
    // from the reader's tree.
    assertThat(GitVersions.changedPaths(git, base))
        .containsExactly("a/x", "b/y", "b", "c/d/new", "c/d", "c");
  }

  @Test
  public void history_versionsEachPathByItsLastChange() throws Exception {
    String[] commits = linearHistory();
    Base base = GitVersions.resolveBase(git, "main", "");
    java.nio.file.Path cacheDir = root.getParent().resolve("cache");

    History history = GitVersions.history(git, base, /* window= */ 2, cacheDir);

    // The window is the second and third commits; a/x was last modified by the second.
    assertThat(history.windowStart()).isEqualTo(1);
    assertThat(history.fileVersions()).containsExactly("a/x", 2, "b/y", 2, "c/d/new", 3);
    // Only additions and deletions touch listings: a/x was modified, so a is not listed.
    assertThat(history.listingVersions()).containsExactly("", 3, "b", 2, "c", 3, "c/d", 3);
    assertThat(cacheDir.resolve(commits[2] + "-2.log").toFile().exists()).isTrue();

    // A second read comes from the cache, even without a repository.
    Git broken = new Git("/nonexistent/git", root);
    assertThat(GitVersions.history(broken, base, 2, cacheDir)).isEqualTo(history);
  }

  @Test
  public void history_wholeHistoryStartsAtZero() throws Exception {
    linearHistory();
    Base base = GitVersions.resolveBase(git, "main", "");

    History history =
        GitVersions.history(git, base, /* window= */ 100, root.getParent().resolve("cache"));

    assertThat(history.windowStart()).isEqualTo(0);
    assertThat(history.fileVersions()).containsEntry("a/x", 2);
  }

  @Test
  public void changedListings_onlyAdditionsDeletionsAndRetypes() throws Exception {
    write("a/edited", "1");
    write("a/deleted", "1");
    write("b/retyped", "1");
    write("c/kept", "1");
    commit("first");
    Base base = GitVersions.resolveBase(git, "main", "");
    write("a/edited", "2");
    Files.delete(root.resolve("a/deleted"));
    Files.delete(root.resolve("b/retyped"));
    Files.createSymbolicLink(root.resolve("b/retyped"), root.resolve("c/kept"));
    write("d/e/added", "1");

    var changed = GitVersions.changedPaths(git, base);
    assertThat(GitVersions.changedListings(git, base, changed))
        .containsExactly("a", "b", "", "d", "d/e");
  }

  @Test
  public void versionGetter_uncommittedChangesArePastEveryCommit() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workspace = fs.getPath("/ws");
    var getter =
        new GitLongVersionGetter(
            workspace,
            immediateFuture(new History(10, ImmutableMap.of("a/x", 12), ImmutableMap.of())),
            immediateFuture(
                new Uncommitted(ImmutableSet.of("a/x", "a/gone"), ImmutableSet.of("a"))));

    assertThat(getter.getFilePathOrSymlinkVersion(workspace.getRelative("a/x")))
        .isEqualTo(GitLongVersionGetter.UNCOMMITTED);
    assertThat(getter.getNonexistentPathVersion(workspace.getRelative("a/gone")))
        .isEqualTo(GitLongVersionGetter.UNCOMMITTED);
    assertThat(getter.getDirectoryListingVersion(workspace.getRelative("a")))
        .isEqualTo(GitLongVersionGetter.UNCOMMITTED);
    assertThat(getter.getFilePathOrSymlinkVersion(workspace.getRelative("a/y"))).isEqualTo(10);
  }

  @Test
  public void versionGetter() throws Exception {
    FileSystem fs = new InMemoryFileSystem(DigestHashFunction.SHA256);
    Path workspace = fs.getPath("/ws");
    History history =
        new History(
            /* windowStart= */ 10,
            ImmutableMap.of("a/x", 12, "gone", 11),
            ImmutableMap.of("a", 12));
    var getter =
        new GitLongVersionGetter(
            workspace, immediateFuture(history), immediateFuture(Uncommitted.NONE));

    assertThat(getter.getFilePathOrSymlinkVersion(workspace.getRelative("a/x"))).isEqualTo(12);
    assertThat(getter.getFilePathOrSymlinkVersion(workspace.getRelative("a/old"))).isEqualTo(10);
    assertThat(getter.getDirectoryListingVersion(workspace.getRelative("a"))).isEqualTo(12);
    assertThat(getter.getDirectoryListingVersion(workspace.getRelative("b"))).isEqualTo(10);
    assertThat(getter.getNonexistentPathVersion(workspace.getRelative("gone"))).isEqualTo(11);
    assertThat(getter.getNonexistentPathVersion(workspace.getRelative("never")))
        .isEqualTo(LongVersionGetter.MINIMAL);
    assertThat(getter.getFilePathOrSymlinkVersion(fs.getPath("/usr/include/stdio.h")))
        .isEqualTo(LongVersionGetter.MINIMAL);
  }
}
