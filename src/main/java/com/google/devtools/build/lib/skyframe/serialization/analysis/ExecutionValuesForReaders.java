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
import java.util.Map;
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

  /** Returns {@code value} as a reader should receive it, or null if it has to be left out. */
  @Nullable
  static ActionExecutionValue forReader(ActionExecutionValue value) {
    if (value instanceof WithRichData) {
      // A runfiles tree: its rich data holds the metadata of everything in the tree, source files
      // included, and the action is not shareable in the first place.
      return null;
    }
    Artifact testStatus = testStatusOutput(value);
    if (testStatus != null) {
      return ExecutionValueCaching.testResultsEnabled()
          ? testResultForReader(value, testStatus)
          : null;
    }
    for (TreeArtifactValue tree : value.getAllTreeArtifactValues().values()) {
      for (FileArtifactValue child : tree.getChildValues().values()) {
        if (!child.isRemote() && !child.isInline()) {
          return null;
        }
      }
    }
    boolean changed = false;
    for (FileArtifactValue file : value.getAllFileValues().values()) {
      if (file.isRemote() || file.isInline()) {
        continue;
      }
      if (!ExecutionValueCaching.localResultsEnabled()) {
        return null;
      }
      if (isUnresolvedSymlink(file)) {
        // Complete as it is: the metadata holds the link's target, which is its whole content. The
        // reader recreates the link from it when it takes the result.
        continue;
      }
      if (!isLocalRegularFile(file)) {
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
      files.put(
          entry.getKey(),
          file.isRemote() || file.isInline() || isUnresolvedSymlink(file)
              ? file
              : FileArtifactValue.createForRemoteFile(
                  file.getDigest(), file.getSize(), REMOTE_CACHE_LOCATION));
    }
    return ActionExecutionValue.create(
        files.buildOrThrow(),
        value.getAllTreeArtifactValues(),
        /* richArtifactData= */ null,
        value.getDiscoveredModules());
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
      ActionExecutionValue value, Artifact testStatus) {
    if (!ExecutionValueCaching.localResultsEnabled()) {
      // The cache status file is always written locally.
      return null;
    }
    for (TreeArtifactValue tree : value.getAllTreeArtifactValues().values()) {
      for (FileArtifactValue child : tree.getChildValues().values()) {
        if (!child.isRemote() && !child.isInline()) {
          return null;
        }
      }
    }
    FileArtifactValue inlineStatus;
    try {
      Path path = testStatus.getPath();
      byte[] content = FileSystemUtils.readContent(path);
      TestResultData data = TestResultData.parseFrom(content, ExtensionRegistryLite.getEmptyRegistry());
      if (!data.getCachable() || !data.getTestPassed()) {
        return null;
      }
      inlineStatus =
          FileArtifactValue.createForInlineFile(
              content, path.getFileSystem().getDigestFunction().getHashFunction());
    } catch (IOException e) {
      // Missing, unreadable or not a status: nothing a reader could report from.
      return null;
    }
    ImmutableMap.Builder<Artifact, FileArtifactValue> files =
        ImmutableMap.builderWithExpectedSize(value.getAllFileValues().size());
    for (Map.Entry<Artifact, FileArtifactValue> entry : value.getAllFileValues().entrySet()) {
      FileArtifactValue file = entry.getValue();
      if (entry.getKey().equals(testStatus)) {
        files.put(testStatus, inlineStatus);
      } else if (file.isRemote()
          || file.isInline()
          // The directory of the test's undeclared outputs. Nothing in a build reads it through
          // its metadata, which says no more than that it is a directory.
          || file.getType() == FileStateType.DIRECTORY) {
        files.put(entry.getKey(), file);
      } else if (isLocalRegularFile(file)) {
        // The log, and the coverage data if there is any.
        files.put(
            entry.getKey(),
            FileArtifactValue.createForRemoteFile(
                file.getDigest(), file.getSize(), REMOTE_CACHE_LOCATION));
      } else {
        return null;
      }
    }
    return ActionExecutionValue.create(
        files.buildOrThrow(),
        value.getAllTreeArtifactValues(),
        /* richArtifactData= */ null,
        value.getDiscoveredModules());
  }

  /** Whether {@code file} is a symlink Bazel does not follow, declared as a symlink output. */
  private static boolean isUnresolvedSymlink(FileArtifactValue file) {
    return file.getType() == FileStateType.SYMLINK;
  }

  /**
   * Whether {@code file} is an ordinary file on the writer's disk with a known digest: not a
   * directory, not a symlink of either kind, and not a marker.
   */
  private static boolean isLocalRegularFile(FileArtifactValue file) {
    return file.getType() == FileStateType.REGULAR_FILE
        && file.getDigest() != null
        && file.getResolvedPath() == null;
  }
}
