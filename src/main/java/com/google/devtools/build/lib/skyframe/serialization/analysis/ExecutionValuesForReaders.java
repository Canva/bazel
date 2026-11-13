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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.TreeArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateType;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue.WithRichData;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.view.test.TestStatus.TestResultData;
import com.google.protobuf.ExtensionRegistryLite;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Turns an action's result into the form a reader of the analysis cache can use without having run
 * the action.
 *
 * <p>A reader that takes a result from the cache has none of the action's outputs on disk. That is
 * the state Bazel is already in for an output it left in the remote cache: the metadata carries the
 * digest and size, which is all a dependent action needs to compute its own key, and the content is
 * fetched only if something has to read it. So every output is described to the reader as remote.
 *
 * <p>For an output the writer really has in the remote cache that is simply true. For one the
 * writer only has on disk, such as what a file write action or a locally run spawn produced, the
 * reader is told the same thing although the content is nowhere it can fetch. A reader that needs
 * that content finds it missing exactly as it finds a blob the remote cache evicted, and recovers
 * the same way: action rewinding runs the generating action, which then skips the cache (see {@code
 * SkyframeActionExecutor#shouldSkipRetrieval}). The reader therefore runs such an action only when
 * something consumes its output, instead of always.
 *
 * <p>An unresolved symlink is written as it is. Its metadata is its content, and the reader puts
 * the link back on disk when it takes the result, since an action that runs locally reads the link
 * rather than the metadata.
 *
 * <p>A test's result is different in that the test action itself reads one of its outputs back:
 * the cache status file, in which it keeps what it reports for a test it does not run again. A
 * reader has no such file. So the content of that file is written into its metadata, and the
 * reader's test action reports from there: see {@code TestRunnerAction#resultRetrieved}. Only the
 * result of a test that passed is written, as only that is ever reused.
 *
 * <p>Results this cannot be done for are left out, and a reader runs those actions itself.
 */
final class ExecutionValuesForReaders {

  /** The location index Bazel gives a file in the remote cache. */
  private static final int REMOTE_CACHE_LOCATION = 1;

  /** The name of the output in which a test action keeps its result: see TestRunnerAction. */
  private static final String TEST_CACHE_STATUS = "test.cache_status";

  private ExecutionValuesForReaders() {}

  /**
   * Counts the results an upload leaves out, by the kind of action and the reason, and keeps one
   * example of each.
   */
  static final class LeftOut {
    private final ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> examples = new ConcurrentHashMap<>();

    void add(String what, Supplier<String> example) {
      counts.computeIfAbsent(what, unused -> new LongAdder()).increment();
      if (!examples.containsKey(what)) {
        examples.putIfAbsent(what, example.get());
      }
    }

    boolean isEmpty() {
      return counts.isEmpty();
    }

    long total() {
      return counts.values().stream().mapToLong(LongAdder::sum).sum();
    }

    /** The {@code limit} most common entries, as "count what (e.g. example)". */
    String summary(int limit) {
      return counts.entrySet().stream()
          .sorted(
              Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> -e.getValue().sum())
                  .thenComparing(Map.Entry::getKey))
          .limit(limit)
          .map(
              e ->
                  String.format(
                      "%d %s (e.g. %s)", e.getValue().sum(), e.getKey(), examples.get(e.getKey())))
          .collect(Collectors.joining("; "));
    }
  }

  /** Returns {@code value} as a reader should receive it, or null if it has to be left out. */
  @Nullable
  static ActionExecutionValue forReader(ActionExecutionValue value) {
    return forReader(value, unused -> {});
  }

  /**
   * As {@link #forReader(ActionExecutionValue)}, and tells {@code whyNot} the reason when the
   * result has to be left out.
   */
  @Nullable
  static ActionExecutionValue forReader(ActionExecutionValue value, Consumer<String> whyNot) {
    if (value instanceof WithRichData) {
      // A runfiles tree: its rich data holds the metadata of everything in the tree, source files
      // included, and the action is not shareable in the first place.
      whyNot.accept("a runfiles tree");
      return null;
    }
    Artifact testStatus = testStatusOutput(value);
    if (testStatus != null) {
      if (!ExecutionValueCaching.testResultsEnabled()) {
        whyNot.accept("test results are not cached");
        return null;
      }
      return testResultForReader(value, testStatus, whyNot);
    }
    ImmutableMap<Artifact, TreeArtifactValue> originalTrees = value.getAllTreeArtifactValues();
    ImmutableMap<Artifact, TreeArtifactValue> trees = treesForReader(originalTrees, whyNot);
    if (trees == null) {
      return null;
    }
    boolean changed = trees != originalTrees;
    for (FileArtifactValue file : value.getAllFileValues().values()) {
      if (file.isRemote() || file.isInline()) {
        continue;
      }
      if (!ExecutionValueCaching.localResultsEnabled()) {
        whyNot.accept("local results are not cached");
        return null;
      }
      if (isUnresolvedSymlink(file)) {
        // Complete as it is: the metadata holds the link's target, which is its whole content. The
        // reader recreates the link from it when it takes the result.
        continue;
      }
      if (!isLocalFileWithDigest(file)) {
        whyNot.accept("an output that is a local " + describe(file));
        return null;
      }
      changed = true;
    }
    if (!changed) {
      return value;
    }
    ImmutableMap.Builder<Artifact, FileArtifactValue> files =
        ImmutableMap.builderWithExpectedSize(value.getAllFileValues().size());
    for (Map.Entry<Artifact, FileArtifactValue> entry : value.getAllFileValues().entrySet()) {
      FileArtifactValue file = entry.getValue();
      files.put(entry.getKey(), isUnresolvedSymlink(file) ? file : asRemote(file));
    }
    return ActionExecutionValue.create(
        files.buildOrThrow(),
        trees,
        /* richArtifactData= */ null,
        value.getDiscoveredModules());
  }

  /**
   * Returns the directory outputs {@code original} with every file in them described as remote:
   * {@code original} itself if none is local, and null, having told {@code whyNot}, if one holds
   * something that cannot be described so.
   */
  @Nullable
  private static ImmutableMap<Artifact, TreeArtifactValue> treesForReader(
      ImmutableMap<Artifact, TreeArtifactValue> original, Consumer<String> whyNot) {
    boolean changed = false;
    for (TreeArtifactValue tree : original.values()) {
      // A directory that is a link to another one is described by what it holds, like a file that
      // is a link to another one.
      boolean local = tree.getResolvedPath().isPresent();
      for (FileArtifactValue child : tree.getChildValues().values()) {
        if (child.isRemote() || child.isInline()) {
          continue;
        }
        if (!isLocalFileWithDigest(child)) {
          whyNot.accept("a directory output holding a local " + describe(child));
          return null;
        }
        local = true;
      }
      if (tree.getArchivedRepresentation().isPresent()) {
        FileArtifactValue archive = tree.getArchivedRepresentation().get().archivedFileValue();
        if (!archive.isRemote() && !archive.isInline()) {
          if (!isLocalFileWithDigest(archive)) {
            whyNot.accept("a directory output archived as a local " + describe(archive));
            return null;
          }
          local = true;
        }
      }
      if (local && !ExecutionValueCaching.localResultsEnabled()) {
        whyNot.accept("local results are not cached");
        return null;
      }
      changed |= local;
    }
    if (!changed) {
      return original;
    }
    ImmutableMap.Builder<Artifact, TreeArtifactValue> trees =
        ImmutableMap.builderWithExpectedSize(original.size());
    for (Map.Entry<Artifact, TreeArtifactValue> entry : original.entrySet()) {
      TreeArtifactValue tree = entry.getValue();
      TreeArtifactValue.Builder builder =
          TreeArtifactValue.newBuilder((TreeArtifact) entry.getKey());
      tree.getChildValues().forEach((child, file) -> builder.putChild(child, asRemote(file)));
      tree.getArchivedRepresentation()
          .ifPresent(
              archived ->
                  builder.setArchivedRepresentation(
                      archived.archivedTreeFileArtifact(),
                      asRemote(archived.archivedFileValue())));
      trees.put(entry.getKey(), builder.build());
    }
    return trees.buildOrThrow();
  }

  /** Describes a file with a known digest as one in the remote cache, if it is not already. */
  private static FileArtifactValue asRemote(FileArtifactValue file) {
    return file.isRemote() || file.isInline()
        ? file
        : FileArtifactValue.createForRemoteFile(
            file.getDigest(), file.getSize(), REMOTE_CACHE_LOCATION);
  }

  /** Whether {@code value} is the result of a test action. */
  static boolean isTestResult(ActionExecutionValue value) {
    return testStatusOutput(value) != null;
  }

  @Nullable
  private static Artifact testStatusOutput(ActionExecutionValue value) {
    for (Artifact output : value.getAllFileValues().keySet()) {
      if (output.getExecPath().getBaseName().equals(TEST_CACHE_STATUS)) {
        return output;
      }
    }
    return null;
  }

  /**
   * Returns the result of a test as a reader should receive it, or null if the test did not pass
   * or the result has to be left out for another reason.
   */
  @Nullable
  private static ActionExecutionValue testResultForReader(
      ActionExecutionValue value, Artifact testStatus, Consumer<String> whyNot) {
    if (!ExecutionValueCaching.localResultsEnabled()) {
      // The cache status file is always written locally.
      whyNot.accept("local results are not cached");
      return null;
    }
    // The directory of the test's undeclared outputs, and of its coverage data if there is any.
    ImmutableMap<Artifact, TreeArtifactValue> trees =
        treesForReader(value.getAllTreeArtifactValues(), whyNot);
    if (trees == null) {
      return null;
    }
    FileArtifactValue inlineStatus;
    try {
      Path path = testStatus.getPath();
      byte[] content = FileSystemUtils.readContent(path);
      TestResultData data =
          TestResultData.parseFrom(content, ExtensionRegistryLite.getEmptyRegistry());
      if (!data.getCachable() || !data.getTestPassed()) {
        whyNot.accept(
            "a test with status "
                + data.getStatus()
                + (data.getCachable() ? "" : ", not to be cached"));
        return null;
      }
      inlineStatus =
          FileArtifactValue.createForInlineFile(
              content, path.getFileSystem().getDigestFunction().getHashFunction());
    } catch (IOException e) {
      // Missing, unreadable or not a status: nothing a reader could report from.
      whyNot.accept("a test whose status file cannot be read: " + e.getClass().getSimpleName());
      return null;
    }
    ImmutableMap.Builder<Artifact, FileArtifactValue> files =
        ImmutableMap.builderWithExpectedSize(value.getAllFileValues().size());
    for (Map.Entry<Artifact, FileArtifactValue> entry : value.getAllFileValues().entrySet()) {
      FileArtifactValue file = entry.getValue();
      if (entry.getKey().equals(testStatus)) {
        files.put(testStatus, inlineStatus);
      } else if (file.isRemote() || file.isInline() || isLocalFileWithDigest(file)) {
        // The log, the XML report, and the coverage data if there is any.
        files.put(entry.getKey(), asRemote(file));
      } else if (file.getType() == FileStateType.DIRECTORY) {
        // A directory that is not declared as one. Nothing in a build reads it through its
        // metadata, which says no more than that it is a directory.
        files.put(entry.getKey(), file);
      } else {
        whyNot.accept(
            "a test output "
                + entry.getKey().getExecPath().getBaseName()
                + " that is a local "
                + describe(file));
        return null;
      }
    }
    return ActionExecutionValue.create(
        files.buildOrThrow(), trees, /* richArtifactData= */ null, value.getDiscoveredModules());
  }

  /** What kind of thing {@code file} is, for a message about a result that is left out. */
  private static String describe(FileArtifactValue file) {
    return file.getType()
        + (file.getType() == FileStateType.REGULAR_FILE && file.getDigest() == null
            ? " without a digest"
            : "")
        + (file.getResolvedPath() != null ? " reached through a symlink" : "");
  }

  /** Whether {@code file} is a symlink Bazel does not follow, declared as a symlink output. */
  private static boolean isUnresolvedSymlink(FileArtifactValue file) {
    return file.getType() == FileStateType.SYMLINK;
  }

  /**
   * Whether {@code file} is a file on the writer's disk with a known digest: not a directory, not
   * a link Bazel does not follow, and not a marker. It may be a link to another file, which a
   * symlink action produces: its digest and size are those of the file it leads to, and that is
   * all a reader is told.
   */
  private static boolean isLocalFileWithDigest(FileArtifactValue file) {
    return file.getType() == FileStateType.REGULAR_FILE && file.getDigest() != null;
  }
}
