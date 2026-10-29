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
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.authandtls.AuthAndTLSOptions;
import com.google.devtools.build.lib.authandtls.GoogleAuthUtils;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialHelperEnvironment;
import com.google.devtools.build.lib.authandtls.credentialhelper.CredentialModule;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.WorkspaceBuilder;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.server.FailureDetails.RemoteAnalysisCaching;
import com.google.devtools.build.lib.skyframe.serialization.SerializationModule;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencySerializer;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesOptions;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingServicesSupplier;
import com.google.devtools.build.lib.util.AbruptExitException;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.common.options.OptionsBase;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Points Bazel's remote analysis caching at the analysis cache service.
 *
 * <p>Everything is driven by Bazel's existing flags: with {@code
 * --experimental_remote_analysis_cache} unset, the supplier never connects and the module behaves
 * exactly like the {@link SerializationModule} it replaces.
 */
public final class SkycacheModule extends SerializationModule {

  private final SkycacheServicesSupplier supplier = new SkycacheServicesSupplier();

  /** Shared with the remote module so one helper invocation serves both. */
  @Nullable private CredentialModule credentialModule;

  /** Held from {@code beforeCommand} because {@link #afterCommand} is handed no environment. */
  @Nullable private ExtendedEventHandler eventHandler;

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
        ? ImmutableList.of(AuthAndTLSOptions.class, RemoteAnalysisCachingServicesOptions.class)
        : ImmutableList.of();
  }

  /**
   * Supplies the version oracle the frontier serializer requires, and the caller's credentials.
   *
   * <p>Without the oracle, upload dies on {@code NullPointerException("missing versionGetter")}:
   * {@code FrontierSerializer} falls back only to a test injection, and Bazel ships no
   * implementation for ordinary use.
   */
  @Override
  public void beforeCommand(CommandEnvironment env) throws AbruptExitException {
    eventHandler = env.getReporter();
    env.setVersionGetter(UnchangedLongVersionGetter.INSTANCE);

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

  @Override
  public void afterCommand() {
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
