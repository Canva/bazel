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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versions a git working tree by the first-parent history of a mainline branch.
 *
 * <p>The version of a mainline commit is its <b>ordinal</b>: the number of commits on its
 * first-parent chain. Ordinals increase along the mainline, agree between every clone, and fit the
 * {@code int} versions that {@code VersionedChanges} matches against. They are unique only among
 * commits on the first-parent chain, so a commit reached through a merge's second parent has no
 * version and is rejected.
 *
 * <p>A writer uploads from a clean checkout of a mainline commit, the <b>base</b>, and every key it
 * writes carries the base's ordinal. A reader evaluates at the same base so its keys match, and
 * treats everything that differs between the base and its own working tree as changed.
 */
final class GitVersions {

  /** A mainline commit and its ordinal. */
  record Base(String commit, int ordinal) {}

  /** Tip ordinals by commit, so a warm server pays for the count once per fetch. */
  private static final Map<String, Integer> tipOrdinals = new ConcurrentHashMap<>();

  private GitVersions() {}

  /**
   * Fails unless the working tree is the root of its repository.
   *
   * <p>git reports paths relative to the repository root, and the versions are looked up by
   * workspace-relative path, so the two have to coincide.
   */
  static void checkWorkTreeIsRoot(Git git) throws IOException, InterruptedException {
    String topLevel = git.line("rev-parse", "--show-toplevel");
    if (!Files.isSameFile(java.nio.file.Path.of(topLevel), git.workTree())) {
      throw new IOException(
          String.format(
              "the workspace %s is not the root of its git repository %s",
              git.workTree(), topLevel));
    }
  }

  /**
   * Resolves the base to version at.
   *
   * @param mainline the branch whose first-parent history defines ordinals
   * @param base a revision to use as the base, or empty for the merge base of {@code HEAD} and
   *     {@code mainline}
   */
  static Base resolveBase(Git git, String mainline, String base)
      throws IOException, InterruptedException {
    String tip = resolveCommit(git, mainline);
    String commit =
        base.isEmpty()
            ? git.line("merge-base", resolveCommit(git, "HEAD"), tip)
            : resolveCommit(git, base);
    Integer tipOrdinal = tipOrdinals.get(tip);
    if (tipOrdinal == null) {
      tipOrdinal = Integer.parseInt(git.line("rev-list", "--count", "--first-parent", tip));
      tipOrdinals.put(tip, tipOrdinal);
    }
    // Every first-parent commit of the tip that is not an ancestor of the base. If the base is on
    // the chain this is exactly its distance from the tip, which the walk below confirms.
    int distance =
        Integer.parseInt(git.line("rev-list", "--count", "--first-parent", commit + ".." + tip));
    String atDistance =
        git.line("rev-list", "--first-parent", "--skip=" + distance, "--max-count=1", tip);
    if (!atDistance.equals(commit)) {
      throw new IOException(
          String.format(
              "%s is not on the first-parent history of %s (%s); fetch %s or pick a mainline"
                  + " commit",
              commit, mainline, tip, mainline));
    }
    return new Base(commit, tipOrdinal - distance);
  }

  private static String resolveCommit(Git git, String revision)
      throws IOException, InterruptedException {
    try {
      return git.line("rev-parse", "--verify", "--quiet", revision + "^{commit}");
    } catch (IOException e) {
      throw new IOException("cannot resolve '" + revision + "' to a commit", e);
    }
  }

  /**
   * Returns every workspace-relative path whose file or directory differs between the base and the
   * working tree.
   *
   * <p>That is each path added, deleted, modified or changed in type, in either direction, by the
   * commits between the base and {@code HEAD} or by the working tree, including untracked files
   * outside {@code .gitignore}. A directory created or removed along the way is included too, which
   * is how a listing that gained or lost a subdirectory is invalidated: registering a path only
   * invalidates the listing of its immediate parent.
   */
  static ImmutableSet<String> changedPaths(Git git, Base base)
      throws IOException, InterruptedException {
    var paths = new LinkedHashSet<String>();
    String head = resolveCommit(git, "HEAD");
    if (!head.equals(base.commit())) {
      // A tree diff, so this is symmetric: the reader may be ahead of, behind, or beside the base.
      for (String path :
          split(git.run("diff", "--name-only", "--no-renames", "-z", base.commit(), head))) {
        paths.add(path);
      }
    }
    paths.addAll(statusPaths(git));

    // Ancestors whose existence differs between the two trees.
    var ancestors = new LinkedHashSet<String>();
    for (String path : paths) {
      for (String dir = parent(path); !dir.isEmpty(); dir = parent(dir)) {
        if (!ancestors.add(dir)) {
          break;
        }
      }
    }
    Set<String> inBase = directoriesIn(git, base.commit(), ancestors);
    var changed = ImmutableSet.<String>builder().addAll(paths);
    for (String path : paths) {
      for (String dir = parent(path); !dir.isEmpty(); dir = parent(dir)) {
        boolean inWorkTree = Files.isDirectory(git.workTree().resolve(dir));
        if (inWorkTree && inBase.contains(dir)) {
          break;
        }
        changed.add(dir);
      }
    }
    return changed.build();
  }

  /** How many paths one {@code git ls-tree} is handed, to stay well inside the argument limit. */
  private static final int LS_TREE_BATCH = 500;

  private enum EntryType {
    ABSENT,
    FILE,
    DIRECTORY,
    SYMLINK
  }

  /**
   * Returns every directory whose listing differs between the base and the working tree.
   *
   * <p>A listing holds names and entry types, not contents, so only {@code changedPaths} that were
   * added, deleted or changed type reach their parent's listing; an edit in place does not. That
   * matters to a writer, which leaves out every entry reaching a changed listing: an edited BUILD
   * file must not cost every glob in its package.
   *
   * @param changedPaths as {@link #changedPaths} returns them
   */
  static ImmutableSet<String> changedListings(Git git, Base base, Set<String> changedPaths)
      throws IOException, InterruptedException {
    Map<String, EntryType> inBase = new HashMap<>();
    List<String> paths = new ArrayList<>(changedPaths);
    for (int start = 0; start < paths.size(); start += LS_TREE_BATCH) {
      var args = new ArrayList<String>();
      // Global, so it precedes the subcommand: paths are names here, not patterns.
      args.add("--literal-pathspecs");
      args.add("ls-tree");
      args.add("-z");
      args.add("--full-tree");
      args.add(base.commit());
      args.add("--");
      args.addAll(paths.subList(start, Math.min(paths.size(), start + LS_TREE_BATCH)));
      // "<mode> SP <type> SP <object> TAB <path>"
      for (String record : split(git.run(args.toArray(String[]::new)))) {
        int tab = record.indexOf('\t');
        if (tab < 0) {
          throw new IOException("malformed git ls-tree record: " + record);
        }
        String mode = record.substring(0, record.indexOf(' '));
        inBase.put(
            record.substring(tab + 1),
            switch (mode) {
              case "120000" -> EntryType.SYMLINK;
              case "040000", "160000" -> EntryType.DIRECTORY; // A submodule is a directory here.
              default -> EntryType.FILE;
            });
      }
    }
    var listings = ImmutableSet.<String>builder();
    for (String path : changedPaths) {
      if (inBase.getOrDefault(path, EntryType.ABSENT) != inWorkTree(git, path)) {
        listings.add(parent(path));
      }
    }
    return listings.build();
  }

  private static EntryType inWorkTree(Git git, String path) {
    java.nio.file.Path resolved = git.workTree().resolve(path);
    if (Files.isSymbolicLink(resolved)) {
      return EntryType.SYMLINK;
    }
    if (Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
      return EntryType.DIRECTORY;
    }
    return Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) ? EntryType.FILE : EntryType.ABSENT;
  }

  /** Returns which of {@code dirs} are directories in {@code commit}. */
  private static Set<String> directoriesIn(Git git, String commit, Set<String> dirs)
      throws IOException, InterruptedException {
    if (dirs.isEmpty()) {
      return ImmutableSet.of();
    }
    var request = new StringBuilder();
    for (String dir : dirs) {
      request.append(commit).append(':').append(dir).append('\n');
    }
    // One line per request in order: "<object> <type> <size>", or "<request> missing".
    String[] lines =
        new String(
                git.run(
                    request.toString().getBytes(UTF_8),
                    "cat-file",
                    "--batch-check=%(objecttype)"),
                UTF_8)
            .split("\n", -1);
    var result = ImmutableSet.<String>builder();
    int i = 0;
    for (String dir : dirs) {
      if (i < lines.length && lines[i].equals("tree")) {
        result.add(dir);
      }
      i++;
    }
    return result.build();
  }

  /** Paths {@code git status} reports, relative to the repository root. */
  private static Set<String> statusPaths(Git git) throws IOException, InterruptedException {
    var paths = new LinkedHashSet<String>();
    for (String record :
        split(git.run("status", "--porcelain=v2", "-z", "--untracked-files=all", "--no-renames"))) {
      switch (record.charAt(0)) {
        // "1 XY sub mH mI mW hH hI <path>"
        case '1' -> paths.add(field(record, 8));
        // "u XY sub m1 m2 m3 mW h1 h2 h3 <path>"
        case 'u' -> paths.add(field(record, 10));
        // "? <path>"
        case '?' -> paths.add(record.substring(2));
        default -> throw new IOException("unexpected git status record: " + record);
      }
    }
    return paths;
  }

  /** Returns everything after the first {@code n} space-separated fields of {@code record}. */
  private static String field(String record, int n) throws IOException {
    int start = 0;
    for (int i = 0; i < n; i++) {
      start = record.indexOf(' ', start) + 1;
      if (start == 0) {
        throw new IOException("malformed git status record: " + record);
      }
    }
    return record.substring(start);
  }

  /**
   * The versions of every path the most recent {@code window} first-parent commits of the base
   * touched.
   *
   * @param windowStart the ordinal of the commit before the window. Every path not in {@code
   *     fileVersions} is unchanged since it.
   * @param fileVersions the ordinal that last added, modified, deleted or retyped each path
   * @param listingVersions the ordinal that last added, deleted or retyped anything beneath each
   *     directory
   */
  record History(
      int windowStart,
      ImmutableMap<String, Integer> fileVersions,
      ImmutableMap<String, Integer> listingVersions) {}

  /**
   * Reads the first-parent history of the base, most recent {@code window} commits.
   *
   * @param cacheDir where the log of the last base read is kept, since a writer starts on a cold
   *     server every time and this is the slowest git command it runs
   */
  static History history(Git git, Base base, int window, java.nio.file.Path cacheDir)
      throws IOException, InterruptedException {
    java.nio.file.Path cached = cacheDir.resolve(base.commit() + "-" + window + ".log");
    byte[] log;
    try {
      log = Files.readAllBytes(cached);
    } catch (NoSuchFileException e) {
      log =
          git.run(
              "log",
              "--first-parent",
              "--diff-merges=first-parent",
              "--no-renames",
              "--name-status",
              "-z",
              "--format=%x01%H",
              "--max-count=" + window,
              base.commit());
      Files.createDirectories(cacheDir);
      try (var stale = Files.newDirectoryStream(cacheDir)) {
        for (java.nio.file.Path entry : stale) {
          Files.deleteIfExists(entry);
        }
      }
      java.nio.file.Path partial = Files.createTempFile(cacheDir, "log", ".tmp");
      Files.write(partial, log);
      Files.move(partial, cached, StandardCopyOption.ATOMIC_MOVE);
    }
    return parseHistory(log, base.ordinal());
  }

  /**
   * Parses {@code git log -z --name-status --format=%x01%H}, newest commit first.
   *
   * <p>Each commit is a {@code \x01<hash>} token followed by alternating status and path tokens.
   * Since the newest commit comes first, the first version recorded for a path is its latest.
   */
  static History parseHistory(byte[] log, int baseOrdinal) throws IOException {
    var fileVersions = new HashMap<String, Integer>();
    var listingVersions = new HashMap<String, Integer>();
    int commits = 0;
    int ordinal = baseOrdinal + 1;
    List<String> tokens = split(log);
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i).stripLeading();
      if (token.isEmpty()) {
        continue;
      }
      if (token.charAt(0) == '\u0001') {
        commits++;
        ordinal--;
        continue;
      }
      if (commits == 0 || ++i == tokens.size()) {
        throw new IOException("malformed git log near " + token);
      }
      String path = tokens.get(i);
      fileVersions.putIfAbsent(path, ordinal);
      if (token.charAt(0) != 'M') {
        // Over-approximated: an addition also creates any missing directories above it, and
        // each of those changes its own parent's listing. Versions only need to be canonical, not
        // tight, so every ancestor takes the change.
        for (String dir = parent(path); ; dir = parent(dir)) {
          listingVersions.putIfAbsent(dir, ordinal);
          if (dir.isEmpty()) {
            break;
          }
        }
      }
    }
    return new History(
        baseOrdinal - commits,
        ImmutableMap.copyOf(fileVersions),
        ImmutableMap.copyOf(listingVersions));
  }

  /** Splits NUL-separated output, dropping the empty token after a trailing NUL. */
  private static List<String> split(byte[] output) {
    var tokens = new ArrayList<String>();
    int start = 0;
    for (int i = 0; i < output.length; i++) {
      if (output[i] == 0) {
        tokens.add(new String(output, start, i - start, UTF_8));
        start = i + 1;
      }
    }
    if (start < output.length) {
      tokens.add(new String(output, start, output.length - start, UTF_8));
    }
    return tokens;
  }

  private static String parent(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash);
  }
}
