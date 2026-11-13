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

import com.google.auth.Credentials;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.ServerBuilder;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.skycache.GitVersions.History;
import com.google.devtools.build.lib.skyframe.IncrementalPackageRoots;
import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.skyframe.serialization.Fingerprinter;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ExecutionValueCaching;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.IntVersion;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.annotation.Nullable;

/**
 * Points Bazel's remote analysis caching at the analysis cache service.
 *
 * <p>Everything is driven by Bazel's existing flags: with {@code
 * --experimental_remote_analysis_cache} unset, the supplier never connects and the module behaves
 * exactly like the {@link SerializationModule} it replaces.
 *
 * <p>With the cache set, keys are versioned by the workspace's git history (see {@link
 * GitVersions}) and every lookup is checked against what changed since the entry was written,
 * unless {@code --canva_skycache_git} is empty.
 */
public final class SkycacheModule extends SerializationModule {

  private final SkycacheServicesSupplier supplier = new SkycacheServicesSupplier();

  /** Shared with the remote module so one helper invocation serves both. */
  @Nullable private CredentialModule credentialModule;

  /** Held from {@code beforeCommand} because {@link #afterCommand} is handed no environment. */
  @Nullable private ExtendedEventHandler eventHandler;

  /**
   * How many first-parent commits of history version an upload.
   *
   * <p>Paths untouched in the window share the version of its start, which is sound and costs only
   * sharing between bases; 5,000 commits read in about five seconds on the monorepo.
   */
  private static final int HISTORY_WINDOW = 5000;

  /** Runs git off the command thread, for what is only needed once analysis is under way. */
  private final ListeningExecutorService gitExecutor =
      MoreExecutors.listeningDecorator(
          Executors.newCachedThreadPool(
              new ThreadFactoryBuilder().setNameFormat("skycache-git-%d").setDaemon(true).build()));

  /** The current command's version, read by {@link SkycacheDiffAwarenessModule}'s views. */
  @Nullable private volatile WorkspaceInfoFromDiff workspaceInfo;

  /** Git work still running for the current command. */
  private final List<Future<?>> pendingGit = new ArrayList<>();

  /**
   * Versions every key at a mainline commit's ordinal. The client id follows, as {@code
   * LongVersionClientId(ordinal)}, since the info has no snapshot.
   */
  private record GitWorkspaceInfo(int ordinal) implements WorkspaceInfoFromDiff {
    @Override
    public IntVersion getEvaluatingVersion() {
      return IntVersion.of(ordinal);
    }
  }

  @Override
  protected RemoteAnalysisCachingServicesSupplier getAnalysisCachingServicesSupplier(
      BlazeRuntime runtime) {
    return supplier;
  }

  @Override
  public void serverInit(OptionsParsingResult startupOptions, ServerBuilder builder) {
    builder.addInfoItems(new SkycacheInfoItems.Enabled(), new SkycacheInfoItems.InstallMd5());
  }

  @Override
  public void workspaceInit(
      BlazeRuntime runtime, BlazeDirectories directories, WorkspaceBuilder builder) {
    super.workspaceInit(runtime, directories, builder);
    credentialModule = runtime.getBlazeModule(CredentialModule.class);
  }

  /**
   * Declares the auth options and the cache's batching options, which no build command otherwise
   * declares.
   *
   * <p>The parser rejects reading an options class a command did not declare. {@code
   * --credential_helper} lives in {@link AuthAndTLSOptions}, which reaches only commands the remote
   * module claims, and the batch, deadline and in-flight flags live in {@link
   * RemoteAnalysisCachingServicesOptions}, which upstream registers nowhere.
   */
  @Override
  public Iterable<Class<? extends OptionsBase>> getCommandOptions(String commandName) {
    return "build".equals(commandName)
        ? ImmutableList.of(
            AuthAndTLSOptions.class,
            RemoteAnalysisCachingServicesOptions.class,
            SkycacheFetchOptions.class,
            SkycacheGitOptions.class,
            SkycacheLocalOptions.class,
            SkycacheMirrorOptions.class)
        : ImmutableList.of();
  }

  /** Returns the current command's workspace version, or null if it is not versioned. */
  @Nullable
  WorkspaceInfoFromDiff getWorkspaceInfo() {
    return workspaceInfo;
  }

  /**
   * Supplies the version oracle the frontier serializer requires, the workspace version, and the
   * caller's credentials.
   *
   * <p>Without the oracle, upload dies on {@code NullPointerException("missing versionGetter")}:
   * {@code FrontierSerializer} falls back only to a test injection, and Bazel ships no
   * implementation for ordinary use. The unchanged oracle stays in place for commands git does
   * not version.
   */
  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    eventHandler = env.getReporter();
    env.setVersionGetter(UnchangedLongVersionGetter.INSTANCE);
    workspaceInfo = null;
    supplier.setValidation(null);
    supplier.setBase(null, immediateFuture(ImmutableSet.of()));
    FileDependencySerializer.setMaxDependencyVersion(FileDependencySerializer.UNBOUNDED);

    // An open-source output base is built from absolute symlinks, which the frontier serializer
    // rejects outright. Tolerating them is only correct alongside this cache, so it follows the
    // same flag the supplier does and a stock build keeps the assertion.
    RemoteAnalysisCachingOptions options =
        env.getOptions().getOptions(RemoteAnalysisCachingOptions.class);
    SkycacheLocalOptions localOptions = env.getOptions().getOptions(SkycacheLocalOptions.class);
    boolean enabled =
        options != null
            && (!nullToEmpty(options.getRemoteAnalysisCache()).isEmpty()
                || (localOptions != null && !localOptions.getLocalPath().isEmpty()));
    FileDependencySerializer.setTolerateAbsoluteSymlinks(enabled);
    // Execution values are served as misses anyway, and some cannot be serialized: see
    // ExecutionValueCaching. A stock build keeps both its upload and its lookups.
    SkycacheFetchOptions fetchOptions = env.getOptions().getOptions(SkycacheFetchOptions.class);
    ExecutionValueCaching.setEnabled(
        !enabled || (fetchOptions != null && fetchOptions.getExecutionValues()));
    ExecutionValueCaching.setLocalResultsEnabled(
        fetchOptions == null || fetchOptions.getLocalActionResults());
    ExecutionValueCaching.setTestResultsEnabled(
        enabled && fetchOptions != null && fetchOptions.getTestResults());
    // A reader has the configured targets of its dependencies without their packages, and still
    // needs a root for the files of an external repository that an action reports having read: see
    // IncrementalPackageRoots. A stock build keeps its lookup as it was.
    IncrementalPackageRoots.setUnloadedExternalPackagesDirectory(
        enabled ? env.getDirectories().getOutputBase().getRelative("external") : null);
    // Paths under external/ are versioned through the module files rather than followed; see
    // RepositoryBoundary. Like the above, a stock build keeps the serializer's original behaviour.
    FileDependencySerializer.setRepositoryBoundary(
        enabled && env.getWorkspace() != null
            ? new FileDependencySerializer.RepositoryBoundary(
                env.getWorkspace(),
                env.getDirectories()
                    .getOutputBase()
                    .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION))
            : null);
    if (!enabled) {
      return;
    }
    if (localOptions != null && !localOptions.getLocalPath().isEmpty()) {
      checkLocalPath(env, localOptions.getLocalPath());
    }
    SkycacheGitOptions gitOptions = env.getOptions().getOptions(SkycacheGitOptions.class);
    if (gitOptions != null && !gitOptions.getGit().isEmpty() && env.getWorkspace() != null) {
      versionFromGit(env, options.getMode(), gitOptions);
    }

    AuthAndTLSOptions authAndTlsOptions = env.getOptions().getOptions(AuthAndTLSOptions.class);
    if (authAndTlsOptions == null || credentialModule == null) {
      // Both are present for every command that can reach this; a null means the module graph
      // changed under it, and guessing a credential-free channel would fail far from the cause.
      throw abruptExit("remote analysis caching could not reach Bazel's credential machinery");
    }

    // The same path --remote_cache takes: a credential helper per --credential_helper, falling
    // back to .netrc and to Google application-default credentials. The cache is shared with the
    // remote module, so a helper is invoked once per URI per build rather than once per consumer.
    Credentials credentials;
    try {
      credentials =
          GoogleAuthUtils.newCredentials(
              CredentialHelperEnvironment.newBuilder()
                  .setEventReporter(env.getReporter())
                  .setWorkspacePath(env.getWorkspace())
                  .setClientEnvironment(env::getClientEnv)
                  .setHelperExecutionTimeout(authAndTlsOptions.getCredentialHelperTimeout())
                  .build(),
              credentialModule.getCredentialCache(),
              env.getCommandLinePathFactory(),
              env.getRuntime().getFileSystem(),
              authAndTlsOptions);
    } catch (IOException e) {
      throw abruptExit("resolving credentials for the remote analysis cache: " + e.getMessage());
    }
    supplier.setCredentials(authAndTlsOptions, credentials);
  }

  /**
   * Versions the command by the mainline commit it is based on.
   *
   * <p>Every key a command writes claims that commit's content, so a command that writes leaves out
   * whatever its own changes against the commit reach: their versions are past it, and the
   * serializer drops entries depending on a version past the one they are keyed at. A command that
   * reads checks each entry against the same changes. If the workspace cannot be versioned at all,
   * a write fails and every read is a miss.
   */
  private void versionFromGit(
      CommandEnvironment env, RemoteAnalysisCacheMode mode, SkycacheGitOptions gitOptions)
      throws AbruptExitException {
    boolean writes = mode.isUploadEnabled();
    boolean reads = mode.isRetrievalEnabled();
    Git git =
        new Git(gitOptions.getGit(), java.nio.file.Path.of(env.getWorkspace().getPathString()));
    CompressionService compressionService =
        env.getRuntime().getBlazeService(CompressionService.class);
    Fingerprinter fingerprinter = env.getBlazeWorkspace().getFingerprinterForAnalysisCaching();
    GitVersions.Base base;
    try {
      GitVersions.checkWorkTreeIsRoot(git);
      base = GitVersions.resolveBase(git, gitOptions.getMainline(), gitOptions.getBase());
    } catch (IOException e) {
      if (writes) {
        throw abruptExit(
            String.format(
                "remote analysis cache uploads need a commit on the first-parent history of %s"
                    + " to version them: %s",
                gitOptions.getMainline(), e.getMessage()));
      }
      env.getReporter()
          .handle(
              Event.warn(
                  String.format(
                      "Analysis cache: every entry will be a miss, because this workspace cannot"
                          + " be versioned against %s: %s",
                      gitOptions.getMainline(), e.getMessage())));
      supplier.setValidation(
          new SkycacheServicesSupplier.Validation(
              0, immediateFailedFuture(e), compressionService, fingerprinter));
      return;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw abruptExit("interrupted while versioning the workspace with git");
    }

    workspaceInfo = new GitWorkspaceInfo(base.ordinal());
    env.getReporter()
        .handle(
            Event.info(
                String.format(
                    "Analysis cache: versioned at %s, commit %d of %s",
                    base.commit(), base.ordinal(), gitOptions.getMainline())));
    // Off the command thread: analysis needs neither, and on the monorepo each takes seconds.
    ListenableFuture<ImmutableSet<String>> changes =
        gitExecutor.submit(() -> GitVersions.changedPaths(git, base));
    pendingGit.add(changes);
    if (writes) {
      java.nio.file.Path cacheDir =
          java.nio.file.Path.of(
              env.getDirectories().getOutputBase().getRelative("skycache/git-history").toString());
      ListenableFuture<History> history =
          gitExecutor.submit(() -> GitVersions.history(git, base, HISTORY_WINDOW, cacheDir));
      pendingGit.add(history);
      ListenableFuture<GitLongVersionGetter.Uncommitted> uncommitted =
          Futures.transformAsync(
              changes,
              paths ->
                  gitExecutor.submit(
                      () ->
                          new GitLongVersionGetter.Uncommitted(
                              paths, GitVersions.changedListings(git, base, paths))),
              directExecutor());
      pendingGit.add(uncommitted);
      env.setVersionGetter(new GitLongVersionGetter(env.getWorkspace(), history, uncommitted));
      FileDependencySerializer.setMaxDependencyVersion(base.ordinal());
    }
    if (reads) {
      // Which listings changed is asked of git separately. Taking every changed file to have
      // changed its directory's listing would invalidate the package of every file edited in
      // place, and the analysis of everything above it.
      ListenableFuture<ValidatingAnalysisCacheClient.ClientChanges> clientChanges =
          Futures.transformAsync(
              changes,
              paths ->
                  gitExecutor.submit(
                      () ->
                          new ValidatingAnalysisCacheClient.ClientChanges(
                              paths, GitVersions.changedListings(git, base, paths))),
              directExecutor());
      pendingGit.add(clientChanges);
      supplier.setValidation(
          new SkycacheServicesSupplier.Validation(
              base.ordinal(), clientChanges, compressionService, fingerprinter));
    }
    // A build that both reads and writes is a developer's, between commits: neither asking which
    // commits were uploaded nor claiming its own was is meaningful.
    if (!(reads && writes)) {
      supplier.setBase(base, changes);
    }
  }

  @Override
  public void afterCommand() {
    for (Future<?> pending : pendingGit) {
      pending.cancel(/* mayInterruptIfRunning= */ true);
    }
    pendingGit.clear();
    workspaceInfo = null;
    // Resets the supplier first, since that is what flushes writes and indexes local ones.
    super.afterCommand();
    long shed = supplier.shedReads();
    if (shed > 0 && eventHandler != null) {
      // Shed reads are answered as misses, so the build is correct but quietly less cached than it
      // reports. Nothing else in the stats distinguishes this from a genuinely cold cache.
      eventHandler.handle(
          Event.warn(
              String.format(
                  "Analysis cache: %d reads were evaluated locally because"
                      + " --experimental_remote_analysis_cache_max_in_flight_read_requests was"
                      + " reached.",
                  shed)));
    }
    String validation = supplier.takeValidationSummary();
    if (validation != null && eventHandler != null) {
      eventHandler.handle(Event.info(validation));
    }
    String fetch = supplier.takeFetchSummary();
    if (fetch != null && eventHandler != null) {
      eventHandler.handle(Event.info(fetch));
    }
    String references = supplier.takeReferenceSummary();
    if (references != null && eventHandler != null) {
      eventHandler.handle(Event.info(references));
    }
    String localFailure = supplier.takeLocalStoreFailure();
    if (localFailure != null && eventHandler != null) {
      eventHandler.handle(Event.warn(localFailure));
    }
    eventHandler = null;
  }

  /**
   * Refuses a local store inside the workspace.
   *
   * <p>Inside, its writes would be workspace changes: under EdenFS they materialize every segment
   * in the overlay, and a glob or {@code git status} would walk them.
   */
  private static void checkLocalPath(CommandEnvironment env, String localPath)
      throws AbruptExitException {
    java.nio.file.Path path = java.nio.file.Path.of(localPath);
    if (!path.isAbsolute()) {
      throw abruptExit("--canva_skycache_local_path must be absolute: " + localPath);
    }
    if (env.getWorkspace() == null) {
      return;
    }
    java.nio.file.Path workspace = java.nio.file.Path.of(env.getWorkspace().getPathString());
    if (realPrefix(path).startsWith(realPrefix(workspace))) {
      throw abruptExit(
          String.format(
              "--canva_skycache_local_path %s is inside the workspace %s; put it outside, such as"
                  + " under ~/.cache",
              localPath, workspace));
    }
  }

  /** Resolves the longest existing prefix of {@code path}, so a store yet to be made resolves. */
  private static java.nio.file.Path realPrefix(java.nio.file.Path path) {
    java.nio.file.Path existing = path.normalize();
    java.nio.file.Path rest = existing.getFileSystem().getPath("");
    while (existing != null) {
      try {
        return existing.toRealPath().resolve(rest);
      } catch (IOException e) {
        rest = existing.getFileName().resolve(rest);
        existing = existing.getParent();
      }
    }
    return path.normalize();
  }

  private static AbruptExitException abruptExit(String message) {
    return new AbruptExitException(
        DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(message)
                .setRemoteAnalysisCaching(
                    RemoteAnalysisCaching.newBuilder()
                        .setCode(RemoteAnalysisCaching.Code.REMOTE_ANALYSIS_CACHING_UNKNOWN))
                .build()));
  }
}
