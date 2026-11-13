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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.actions.ActionLookupKey;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.skyframe.ActionExecutionValue;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link ExecutionValuesForReaders}. */
@RunWith(JUnit4.class)
public final class ExecutionValuesForReadersTest {
  private static final byte[] DIGEST = new byte[] {1, 2, 3, 4};
  private static final FileArtifactValue REMOTE =
      FileArtifactValue.createForRemoteFile(DIGEST, /* size= */ 10, /* locationIndex= */ 1);
  private static final FileArtifactValue LOCAL =
      FileArtifactValue.createForNormalFile(DIGEST, /* proxy= */ null, /* size= */ 10);

  private static final ActionLookupKey KEY = ActionsTestUtil.NULL_ARTIFACT_OWNER;
  private static final ActionLookupData ACTION = ActionLookupData.create(KEY, 1);
  private static final ArtifactRoot OUTPUT_ROOT =
      ArtifactRoot.asDerivedRoot(new Scratch().resolve("/execroot"), RootType.OUTPUT, "out");

  @After
  public void resetStaticState() {
    ExecutionValueCaching.setLocalResultsEnabled(true);
  }

  @Test
  public void remoteOutputs_areLeftAsTheyAre() {
    ActionExecutionValue value = files(ImmutableMap.of(output("a"), REMOTE, output("b"), REMOTE));

    assertThat(ExecutionValuesForReaders.forReader(value)).isSameInstanceAs(value);
  }

  @Test
  public void localFile_isDescribedAsRemoteWithTheSameDigestAndSize() {
    DerivedArtifact local = output("local");
    DerivedArtifact remote = output("remote");

    ActionExecutionValue forReader =
        ExecutionValuesForReaders.forReader(files(ImmutableMap.of(local, LOCAL, remote, REMOTE)));

    FileArtifactValue described = forReader.getAllFileValues().get(local);
    assertThat(described.isRemote()).isTrue();
    assertThat(described.getDigest()).isEqualTo(DIGEST);
    assertThat(described.getSize()).isEqualTo(10);
    assertThat(forReader.getAllFileValues().get(remote)).isSameInstanceAs(REMOTE);
  }

  @Test
  public void localFile_isLeftOutWhenLocalResultsAreOff() {
    ExecutionValueCaching.setLocalResultsEnabled(false);

    assertThat(ExecutionValuesForReaders.forReader(files(ImmutableMap.of(output("a"), LOCAL))))
        .isNull();
    assertThat(ExecutionValuesForReaders.forReader(files(ImmutableMap.of(output("a"), REMOTE))))
        .isNotNull();
  }

  @Test
  public void symlinkToALocalFile_isLeftOut() {
    FileArtifactValue symlink =
        FileArtifactValue.createFromExistingWithResolvedPath(
            LOCAL, PathFragment.create("/workspace/pkg/source.txt"));

    assertThat(ExecutionValuesForReaders.forReader(files(ImmutableMap.of(output("a"), symlink))))
        .isNull();
  }

  @Test
  public void unresolvedSymlink_isWrittenAsItIs() throws Exception {
    Path link = new Scratch().resolve("/execroot/out/link");
    link.getParentDirectory().createDirectoryAndParents();
    link.createSymbolicLink(PathFragment.create("../some/target"));
    FileArtifactValue symlink = FileArtifactValue.createForUnresolvedSymlink(link);
    ActionExecutionValue value = files(ImmutableMap.of(output("link"), symlink, output("f"), LOCAL));

    ActionExecutionValue forReader = ExecutionValuesForReaders.forReader(value);

    assertThat(forReader.getAllFileValues().get(output("link"))).isSameInstanceAs(symlink);
    assertThat(forReader.getAllFileValues().get(output("f")).isRemote()).isTrue();
  }

  @Test
  public void localDirectory_isLeftOut() {
    assertThat(
            ExecutionValuesForReaders.forReader(
                files(
                    ImmutableMap.of(
                        output("a"),
                        FileArtifactValue.createForDirectoryWithMtime(/* mtimeNanos= */ 1)))))
        .isNull();
  }

  @Test
  public void treeWithALocalChild_isLeftOut() {
    TreeArtifact tree = TreeArtifact.create(OUTPUT_ROOT, OUTPUT_ROOT.getExecPath().getRelative("t"), KEY);
    tree.setGeneratingActionKey(ACTION);
    TreeArtifactValue localTree =
        TreeArtifactValue.newBuilder(tree)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "child"), LOCAL)
            .build();
    TreeArtifactValue remoteTree =
        TreeArtifactValue.newBuilder(tree)
            .putChild(TreeFileArtifact.createTreeOutput(tree, "child"), REMOTE)
            .build();

    assertThat(ExecutionValuesForReaders.forReader(trees(ImmutableMap.of(tree, localTree))))
        .isNull();
    assertThat(ExecutionValuesForReaders.forReader(trees(ImmutableMap.of(tree, remoteTree))))
        .isNotNull();
  }

  private static ActionExecutionValue files(ImmutableMap<Artifact, FileArtifactValue> files) {
    return ActionExecutionValue.create(
        files,
        /* treeArtifactData= */ ImmutableMap.of(),
        /* richArtifactData= */ null,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private static ActionExecutionValue trees(ImmutableMap<Artifact, TreeArtifactValue> trees) {
    return ActionExecutionValue.create(
        /* artifactData= */ ImmutableMap.of(),
        trees,
        /* richArtifactData= */ null,
        /* discoveredModules= */ NestedSetBuilder.emptySet(Order.STABLE_ORDER));
  }

  private static DerivedArtifact output(String rootRelativePath) {
    DerivedArtifact result =
        DerivedArtifact.create(
            OUTPUT_ROOT, OUTPUT_ROOT.getExecPath().getRelative(rootRelativePath), KEY);
    result.setGeneratingActionKey(ACTION);
    return result;
  }
}
