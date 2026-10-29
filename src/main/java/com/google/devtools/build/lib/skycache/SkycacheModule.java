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
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.skycache.GitVersions.History;
import com.google.devtools.build.lib.skyframe.WorkspaceInfoFromDiff;
import com.google.devtools.build.lib.skyframe.serialization.Fingerprinter;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheMode;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.skyframe.IntVersion;
import com.google.devtools.common.options.OptionsBase;
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
            SkycacheGitOptions.class)
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

    // An open-source output base is built from absolute symlinks, which the frontier serializer
    // rejects outright. Tolerating them is only correct alongside this cache, so it follows the
    // same flag the supplier does and a stock build keeps the assertion.
    RemoteAnalysisCachingOptions options =
        env.getOptions().getOptions(RemoteAnalysisCachingOptions.class);
    boolean enabled =
        options != null
            && options.getRemoteAnalysisCache() != null
            && !options.getRemoteAnalysisCache().isEmpty();
    FileDependencySerializer.setTolerateAbsoluteSymlinks(enabled);
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
   * <p>A command that writes has to be a clean checkout of that commit, since every key it writes
   * claims the commit's content. A command that reads checks each entry against what differs
   * between its base and its own working tree; if the workspace cannot be versioned at all, every
   * entry is a miss.
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
      // A writer's base is HEAD, which is on the mainline exactly when it is its own merge base.
      base =
          GitVersions.resolveBase(
              git, gitOptions.getMainline(), writes ? "" : gitOptions.getBase());
      if (writes) {
        GitVersions.checkClean(git, base);
      }
    } catch (IOException e) {
      if (writes) {
        throw abruptExit(
            String.format(
                "remote analysis cache uploads need a clean checkout of a commit on the"
                    + " first-parent history of %s: %s",
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
    if (writes) {
      java.nio.file.Path cacheDir =
          java.nio.file.Path.of(
              env.getDirectories().getOutputBase().getRelative("skycache/git-history").toString());
      ListenableFuture<History> history =
          gitExecutor.submit(() -> GitVersions.history(git, base, HISTORY_WINDOW, cacheDir));
      pendingGit.add(history);
      env.setVersionGetter(new GitLongVersionGetter(env.getWorkspace(), history));
    }
    if (reads) {
      // A writer is clean at its base, so it has nothing to diff.
      ListenableFuture<ImmutableSet<String>> changes =
          writes
              ? immediateFuture(ImmutableSet.of())
              : gitExecutor.submit(() -> GitVersions.changedPaths(git, base));
      pendingGit.add(changes);
      supplier.setValidation(
          new SkycacheServicesSupplier.Validation(
              base.ordinal(), changes, compressionService, fingerprinter));
    }
  }

  @Override
  public void afterCommand() {
    for (Future<?> pending : pendingGit) {
      pending.cancel(/* mayInterruptIfRunning= */ true);
    }
    pendingGit.clear();
    workspaceInfo = null;
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
    eventHandler = null;
    super.afterCommand();
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
