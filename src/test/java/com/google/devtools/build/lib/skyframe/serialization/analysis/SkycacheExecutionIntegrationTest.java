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
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static java.util.concurrent.ForkJoinPool.commonPool;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.Subscribe;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.ActionLookupData;
import com.google.devtools.build.lib.buildtool.util.BuildIntegrationTestCase;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.compress.CompressionServiceImpl;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutor;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutorOwner;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandStartEvent;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.InMemoryFingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.testutil.TestConstants;
import com.google.devtools.build.lib.versioning.LongVersionGetter;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Executes builds whose analysis comes partly or wholly from the analysis cache.
 *
 * <p>The store outlives each command, as a real service does, and lookups are checked by {@link
 * ValidatingAnalysisCacheClient} against changes the test names.
 */
@RunWith(JUnit4.class)
public final class SkycacheExecutionIntegrationTest extends BuildIntegrationTestCase {

  /** Version of every source, and the bound uploads are held to unless a test lifts it. */
  private static final long COMMITTED = 1;

  /** As the git version getter answers a path the working tree changed. */
  private static final long UNCOMMITTED = Long.MAX_VALUE;

  /** Workspace-relative paths the writer has changed without committing. */
  private static volatile ImmutableSet<String> uncommitted = ImmutableSet.of();

  private static final LongVersionGetter VERSIONS =
      new LongVersionGetter() {
        @Override
        public long getFilePathOrSymlinkVersion(Path path) {
          for (String changed : uncommitted) {
            if (path.getPathString().endsWith("/" + changed)) {
              return UNCOMMITTED;
            }
          }
          return COMMITTED;
        }

        @Override
        public long getDirectoryListingVersion(Path path) {
          return COMMITTED;
        }

        @Override
        public long getNonexistentPathVersion(Path path) {
          return MINIMAL;
        }
      };

  private final FingerprintValueStore store = new InMemoryFingerprintValueStore();
  private final CompressionService compressionService = new CompressionServiceImpl();
  private ImmutableList<String> clientChanges = ImmutableList.of();
  private ValidatingAnalysisCacheClient lastClient;

  @After
  public void resetSerializer() {
    FileDependencySerializer.setTolerateAbsoluteSymlinks(false);
    FileDependencySerializer.setRepositoryBoundary(null);
    FileDependencySerializer.setMaxDependencyVersion(FileDependencySerializer.UNBOUNDED);
    uncommitted = ImmutableSet.of();
  }

  @Before
  public void setUp() {
    runtimeWrapper.registerSubscriber(
        new Object() {
          @Subscribe
          public void commandStart(CommandStartEvent event) {
            getCommandEnvironment().setVersionGetter(VERSIONS);
          }
        });
    // As SkycacheModule sets them for a command with the cache enabled.
    FileDependencySerializer.setTolerateAbsoluteSymlinks(true);
    FileDependencySerializer.setRepositoryBoundary(
        new FileDependencySerializer.RepositoryBoundary(
            getWorkspace(),
            getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)));
    FileDependencySerializer.setMaxDependencyVersion(COMMITTED);
    addOptions("--experimental_frontier_violation_check=disabled_for_testing");
  }

  private final class TestSupplier implements RemoteAnalysisCachingServicesSupplier {
    /** Safe: the common pool is never shut down. */
    private final SafeExecutorOwner commandExecutor = new SafeExecutorOwner(commonPool());

    @Override
    public ListenableFuture<FingerprintValueStore> getFingerprintValueStore() {
      return immediateFuture(store);
    }

    @Override
    public ListenableFuture<ValidatingAnalysisCacheClient> getAnalysisCacheClient() {
      lastClient =
          ValidatingAnalysisCacheClient.create(
              new ValidatingAnalysisCacheClient.Storage(
                  store,
                  commandExecutor,
                  compressionService,
                  getCommandEnvironment().getBlazeWorkspace().getFingerprinterForAnalysisCaching()),
              /* validityHorizon= */ 0,
              immediateFuture(clientChanges));
      return immediateFuture(lastClient);
    }

    @Override
    public SafeExecutor getCommandExecutor() {
      return commandExecutor;
    }

    @Override
    public void resetCommandState() {}
  }

  private final class TestModule extends SerializationModule {
    @Override
    protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier(
        BlazeRuntime runtime) {
      return new TestSupplier();
    }
  }

  @Override
  protected BlazeRuntime.Builder getRuntimeBuilder() throws Exception {
    return super.getRuntimeBuilder()
        .addBlazeModule(new TestModule())
        .addBlazeService(compressionService);
  }

  private void writeTreeScenario() throws IOException {
    write(
        "tree/defs.bzl",
        """
        def _tree_impl(ctx):
            d = ctx.actions.declare_directory(ctx.label.name)
            ctx.actions.run_shell(
                outputs = [d],
                command = "mkdir -p $1 && echo a > $1/a.txt && echo b > $1/b.txt",
                arguments = [d.path],
            )
            return [DefaultInfo(files = depset([d]))]

        tree = rule(implementation = _tree_impl)

        def _list_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".txt")
            src = ctx.files.src[0]
            ctx.actions.run_shell(
                inputs = [src],
                outputs = [out],
                command = "ls $1 > $2",
                arguments = [src.path, out.path],
            )
            return [DefaultInfo(files = depset([out]))]

        list_dir = rule(implementation = _list_impl, attrs = {"src": attr.label()})
        """);
    write("tree/BUILD", "load(':defs.bzl', 'tree')", "tree(name = 't')");
    write(
        "consumer/BUILD",
        "load('//tree:defs.bzl', 'list_dir')",
        "list_dir(name = 'c', src = '//tree:t')");
  }

  private String output(String packagePath, String name) throws IOException {
    Path binRoot =
        getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("bazel-out");
    for (Path config : binRoot.getDirectoryEntries()) {
      Path candidate = config.getRelative("bin").getRelative(packagePath).getRelative(name);
      if (candidate.exists()) {
        return new String(FileSystemUtils.readContentAsLatin1(candidate));
      }
    }
    var found = new StringBuilder();
    if (binRoot.exists()) {
      for (Path file : FileSystemUtils.traverseTree(binRoot, unused -> true)) {
        found.append('\n').append(file.relativeTo(binRoot));
      }
    }
    throw new IOException(
        "no output " + packagePath + "/" + name + "; bazel-out holds:" + found
            + "\ncache hits: "
            + getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits());
  }

  private void upload(String target) throws Exception {
    upload(target, /* execute= */ false);
  }

  /**
   * @param execute whether the upload builds as well, which also serializes execution values whose
   *     outputs exist only in the writer's output base
   */
  private void upload(String target, boolean execute) throws Exception {
    getSkyframeExecutor().resetEvaluator();
    addOptions("--experimental_remote_analysis_cache_mode=upload", execute ? "--build" : "--nobuild");
    buildTarget(target);
    addOptions("--build");
    assertThat(getCommandEnvironment().getRemoteAnalysisCachingEventListener().getSerializedKeys())
        .isNotEmpty();
  }

  private void download(String target) throws Exception {
    getSkyframeExecutor().resetEvaluator();
    getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("bazel-out")
        .deleteTree();
    addOptions("--experimental_remote_analysis_cache_mode=download");
    buildTarget(target);
    assertWithMessage(
            "checker: %s %s %s",
            lastClient.getValidationStats(),
            lastClient.getInvalidationReasons(),
            lastClient.getFirstMissingData())
        .that(getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits())
        .isNotEmpty();
  }

  /** Like the monorepo: tree and consumer in one package, the consumer reaching elsewhere. */
  private void writeSamePackageTreeScenario() throws IOException {
    write(
        "same/defs.bzl",
        """
        def _tree_impl(ctx):
            d = ctx.actions.declare_directory(ctx.label.name)
            ctx.actions.run_shell(
                outputs = [d],
                command = "mkdir -p $1 && echo a > $1/a.txt && echo b > $1/b.txt",
                arguments = [d.path],
            )
            return [DefaultInfo(files = depset([d]))]

        tree = rule(implementation = _tree_impl)

        def _list_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".txt")
            src = ctx.files.src[0]
            ctx.actions.run_shell(
                inputs = [src] + ctx.files.extra,
                outputs = [out],
                command = "ls $1 > $2",
                arguments = [src.path, out.path],
            )
            return [DefaultInfo(files = depset([out]))]

        list_dir = rule(
            implementation = _list_impl,
            attrs = {"src": attr.label(), "extra": attr.label_list(allow_files = True)},
        )
        """);
    write(
        "same/BUILD",
        "load(':defs.bzl', 'list_dir', 'tree')",
        "tree(name = 't')",
        "list_dir(name = 'c', src = ':t', extra = ['//other:f'])");
    write("other/BUILD", "filegroup(name = 'f', srcs = ['f.txt'])");
    write("other/f.txt", "f");
  }

  @Test
  public void treeArtifact_samePackage_consumerInvalidatedThroughADependency()
      throws Exception {
    writeSamePackageTreeScenario();
    upload("//same:c");

    clientChanges = ImmutableList.of("other/BUILD");
    download("//same:c");

    assertThat(output("same", "c.txt")).isEqualTo("a.txt\nb.txt\n");
  }

  /**
   * As rules_go builds GoArchive.transitive: each target adds a struct holding runfiles to its
   * deps' depset, so a leaf's struct sits in more than one node, and the top expands it only when
   * its action runs.
   */
  private void writeSharedStructScenario() throws IOException {
    writeSharedStructScenario("runfiles = ctx.runfiles(files = ctx.files.data)");
  }

  /** @param field the leaf struct's field that compares by identity */
  private void writeSharedStructScenario(String field) throws IOException {
    write(
        "dedup/defs.bzl",
        """
        Info = provider(fields = ["transitive"])

        def _leaf_impl(ctx):
            data = struct(name = ctx.label.name, %s)
            return [Info(transitive = depset([data]))]

        leaf = rule(
            implementation = _leaf_impl,
            attrs = {"data": attr.label_list(allow_files = True)},
        )

        def _mid_impl(ctx):
            data = struct(name = ctx.label.name, runfiles = ctx.runfiles())
            return [Info(transitive = depset(
                [data],
                transitive = [d[Info].transitive for d in ctx.attr.deps],
            ))]

        mid = rule(implementation = _mid_impl, attrs = {"deps": attr.label_list()})

        def _name(data):
            return data.name

        def _top_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".txt")
            args = ctx.actions.args()
            args.add(out)
            args.add_all(
                depset(transitive = [d[Info].transitive for d in ctx.attr.deps]),
                map_each = _name,
            )
            ctx.actions.run_shell(
                outputs = [out],
                command = 'o=$1; shift; for n in "$@"; do echo $n >> $o; done',
                arguments = [args],
            )
            return [DefaultInfo(files = depset([out]))]

        top = rule(implementation = _top_impl, attrs = {"deps": attr.label_list()})
        """.formatted(field));
    write("dedup/data.txt", "data");
    write(
        "dedup/BUILD",
        "load(':defs.bzl', 'leaf', 'mid', 'top')",
        "leaf(name = 'leaf', data = ['data.txt'])",
        "mid(name = 'one', deps = [':leaf'])",
        "mid(name = 'two', deps = [':leaf'])",
        "top(name = 'top', deps = [':one', ':two'])");
  }

  @Test
  public void schemafulProviderDepset_readByAReanalyzedConsumer() throws Exception {
    write(
        "prov/defs.bzl",
        """
        Info = provider(fields = ["files"])

        def _leaf_impl(ctx):
            return [Info(files = depset(ctx.files.srcs))]

        leaf = rule(
            implementation = _leaf_impl,
            attrs = {"srcs": attr.label_list(allow_files = True)},
        )

        def _top_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".txt")
            files = depset(transitive = [d[Info].files for d in ctx.attr.deps])
            ctx.actions.write(out, "\\n".join([f.basename for f in files.to_list()]))
            return [DefaultInfo(files = depset([out]))]

        top = rule(implementation = _top_impl, attrs = {"deps": attr.label_list()})
        """);
    write("prov/BUILD");
    write(
        "lib/BUILD",
        "load('//prov:defs.bzl', 'leaf')",
        "leaf(name = 'leaf', srcs = ['a.txt'], visibility = ['//visibility:public'])");
    write("lib/a.txt", "a");
    write("app/BUILD", "load('//prov:defs.bzl', 'top')", "top(name = 'top', deps = ['//lib:leaf'])");
    upload("//app:top");

    // The consumer is analysed again, on a fresh evaluator whose Info has never been instantiated,
    // and reads the depset out of the cached leaf's provider.
    clientChanges = ImmutableList.of("app/BUILD");
    download("//app:top");

    assertThat(output("app", "top.txt")).isEqualTo("a.txt");
  }

  @Test
  public void uncommittedChange_writerLeavesOutWhatItReaches() throws Exception {
    write("clean/BUILD", "genrule(name = 'a', outs = ['a.txt'], cmd = 'echo a > $@')");
    write(
        "dirty/BUILD",
        "genrule(name = 'b', srcs = ['//clean:a'], outs = ['b.txt'], cmd = 'cp $< $@')");
    uncommitted = ImmutableSet.of("dirty/BUILD");
    upload("//dirty:b");

    // A reader at the same version without the writer's edit: nothing in its own changes would
    // invalidate //dirty:b, so only leaving it out keeps it from being served.
    uncommitted = ImmutableSet.of();
    download("//dirty:b");

    var hits =
        getCommandEnvironment().getRemoteAnalysisCachingEventListener().getCacheHits().stream()
            .map(Object::toString)
            .toList();
    assertThat(hits.stream().anyMatch(key -> key.contains("//clean:a"))).isTrue();
    assertThat(hits.stream().noneMatch(key -> key.contains("//dirty:b"))).isTrue();
    assertThat(output("dirty", "b.txt")).isEqualTo("a\n");
  }

  @Test
  public void depsetOfStructs_reachedTwice_staysDeduplicated() throws Exception {
    writeSharedStructScenario();
    upload("//dedup:top");

    download("//dedup:top");

    assertThat(output("dedup", "top.txt").lines().sorted().toList())
        .containsExactly("leaf", "one", "two");
  }

  @Test
  public void depsetOfStructsHoldingADepset_reachedTwice_staysDeduplicated() throws Exception {
    writeSharedStructScenario("files = depset(ctx.files.data)");
    upload("//dedup:top");

    download("//dedup:top");

    assertThat(output("dedup", "top.txt").lines().sorted().toList())
        .containsExactly("leaf", "one", "two");
  }

  private void writeExternalScenario() throws IOException {
    write(
        "MODULE.bazel",
        "bazel_dep(name = 'ext')",
        "local_path_override(module_name = 'ext', path = 'ext')");
    write("ext/MODULE.bazel", "module(name = 'ext')");
    write("ext/BUILD", "exports_files(['data.txt'])");
    write("ext/data.txt", "from ext");
    write(
        "copy/defs.bzl",
        """
        def _copy_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name + ".txt")
            ctx.actions.run_shell(
                inputs = [ctx.file.src],
                outputs = [out],
                command = "cat $1 > $2",
                arguments = [ctx.file.src.path, out.path],
            )
            return [DefaultInfo(files = depset([out]))]

        copy = rule(
            implementation = _copy_impl,
            attrs = {"src": attr.label(allow_single_file = True)},
        )
        """);
    write("copy/BUILD", "load(':defs.bzl', 'copy')", "copy(name = 'c', src = '@ext//:data.txt')");
  }

  @Test
  public void externalRepositoryInput_everythingCached() throws Exception {
    writeExternalScenario();
    upload("//copy:c");
    // A fresh output base has no execroot symlinks to external repositories.
    getDirectories().getExecRoot(TestConstants.WORKSPACE_NAME).getRelative("external").deleteTree();

    download("//copy:c");

    assertThat(output("copy", "c.txt")).isEqualTo("from ext\n");
  }

  @Test
  public void treeArtifact_everythingCached() throws Exception {
    writeTreeScenario();
    upload("//consumer:c");

    download("//consumer:c");

    assertThat(output("consumer", "c.txt")).isEqualTo("a.txt\nb.txt\n");
  }

  @Test
  public void uploadThatExecuted_readerStillRunsTheActions() throws Exception {
    writeTreeScenario();
    upload("//consumer:c", /* execute= */ true);
    assertWithMessage("expected the upload to carry execution values")
        .that(
            getCommandEnvironment().getRemoteAnalysisCachingEventListener().getSerializedKeys()
                .stream()
                .anyMatch(ActionLookupData.class::isInstance))
        .isTrue();

    download("//consumer:c");

    assertThat(output("consumer", "c.txt")).isEqualTo("a.txt\nb.txt\n");
  }

  @Test
  public void treeArtifact_consumerReanalyzed() throws Exception {
    writeTreeScenario();
    upload("//consumer:c");

    clientChanges = ImmutableList.of("consumer/BUILD");
    download("//consumer:c");

    assertThat(output("consumer", "c.txt")).isEqualTo("a.txt\nb.txt\n");
  }
}
