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

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.canva.skycache.v1.LookupSnapshotRequest;
import com.canva.skycache.v1.LookupSnapshotResponse;
import com.canva.skycache.v1.RecordSnapshotRequest;
import com.canva.skycache.v1.Snapshot;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.SkycacheMetadataParams;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LookupTopLevelTargetsResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LookupTopLevelTargetsResultImpl;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisMetadataWriter;
import com.google.devtools.build.lib.skyframe.serialization.analysis.ValidatingAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.time.Clock;
import java.util.Collection;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Asks the {@link SnapshotIndex} which uploads exist: one snapshot per Bazel install, top-level
 * configuration and mainline commit the priming pipeline has uploaded.
 *
 * <p>A writer records its upload once every entry is acknowledged, so a reader never finds a
 * snapshot whose entries are still on their way. A reader asks before analysis whether its own base
 * was primed. Its keys carry that base's ordinal, so an upload at any other commit cannot hit, and
 * finding none saves the build from checking every entry only to miss.
 *
 * <p>Every call is bounded by {@link SkycacheMetadataParams#TIMEOUT}; neither side fails the build
 * when the index is unreachable.
 */
final class SnapshotIndexClient
    implements RemoteAnalysisMetadataWriter, ValidatingAnalysisCacheClient.TopLevelTargetsLookup {

  /** Suffix keeping stamped uploads apart from unstamped ones, which the keys also separate. */
  private static final String STAMPED_SUFFIX = "+stamped";

  private final SnapshotIndex index;
  private final GitVersions.Base base;
  private final ListenableFuture<ImmutableSet<String>> changes;
  private final Supplier<FingerprintValueStore.Stats> uploadStats;
  private final Clock clock;

  /**
   * @param base the mainline commit this command is versioned at
   * @param changes how the working tree differs from {@code base}
   * @param uploadStats what the command's store has sent, for the snapshot's statistics
   */
  SnapshotIndexClient(
      SnapshotIndex index,
      GitVersions.Base base,
      ListenableFuture<ImmutableSet<String>> changes,
      Supplier<FingerprintValueStore.Stats> uploadStats,
      Clock clock) {
    this.index = index;
    this.base = base;
    this.changes = changes;
    this.uploadStats = uploadStats;
    this.clock = clock;
  }

  @Override
  public boolean addTopLevelTargets(
      String invocationId,
      long evaluatingVersion,
      String configurationHash,
      boolean useFakeStampData,
      String blazeVersion,
      Collection<String> targets,
      Collection<String> configFlags)
      throws IOException {
    if (evaluatingVersion != base.ordinal()) {
      // The keys were written at evaluatingVersion; recording them under the base's commit would
      // point readers at entries they cannot reach.
      throw new IOException(
          String.format(
              "upload was versioned at %d but the workspace is at commit %d (%s)",
              evaluatingVersion, base.ordinal(), base.commit()));
    }
    ImmutableSet<String> changed;
    try {
      // Done by now: the upload needed the same changes to version what it wrote.
      changed = changes.get();
    } catch (ExecutionException e) {
      throw new IOException("reading the working tree's changes: " + e.getCause().getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted reading the working tree's changes", e);
    }
    if (!changed.isEmpty()) {
      // The upload left out what the changes reach, so it is not the commit's analysis.
      throw new IOException(
          String.format(
              "not recording a snapshot of %s: the working tree differs from it in %d paths,"
                  + " starting with %s",
              base.commit(), changed.size(), changed.iterator().next()));
    }
    FingerprintValueStore.Stats stats = uploadStats.get();
    Snapshot snapshot =
        Snapshot.newBuilder()
            .setBazelInstallMd5(installMd5(blazeVersion))
            .setConfigurationHash(configurationKey(configurationHash, useFakeStampData))
            .setMasterOrdinal(evaluatingVersion)
            .setCommit(base.commit())
            .setBazelVersion(blazeVersion)
            .addAllTargets(targets)
            .setRecordedUnixMillis(clock.millis())
            .setEntries(stats.entriesWritten())
            .setBytes(stats.keyBytesSent() + stats.valueBytesSent())
            .build();
    try {
      var unused =
          index
              .record(RecordSnapshotRequest.newBuilder().setSnapshot(snapshot).build())
              .get(SkycacheMetadataParams.TIMEOUT.toMillis(), MILLISECONDS);
    } catch (ExecutionException | TimeoutException e) {
      throw new IOException(
          "recording snapshot of commit " + base.commit() + ": " + describe(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted recording snapshot of commit " + base.commit(), e);
    }
    return true;
  }

  @Override
  public LookupTopLevelTargetsResult lookup(
      long evaluatingVersion,
      String configurationHash,
      boolean useFakeStampData,
      String bazelVersion)
      throws ExecutionException, TimeoutException, InterruptedException {
    LookupSnapshotResponse response;
    try {
      response =
          index
              .lookup(
                  LookupSnapshotRequest.newBuilder()
                      .setBazelInstallMd5(installMd5(bazelVersion))
                      .setConfigurationHash(configurationKey(configurationHash, useFakeStampData))
                      .setMaxMasterOrdinal(evaluatingVersion)
                      .build())
              .get(SkycacheMetadataParams.TIMEOUT.toMillis(), MILLISECONDS);
    } catch (ExecutionException e) {
      if (Status.fromThrowable(e).getCode() == Status.Code.UNIMPLEMENTED) {
        // A service without the index still serves entries, and each is checked as it is read.
        return result(
            TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH,
            "the analysis cache service keeps no snapshot index; checking entries as they are"
                + " read");
      }
      throw e;
    }
    return interpret(response, evaluatingVersion, configurationHash);
  }

  @VisibleForTesting
  static LookupTopLevelTargetsResult interpret(
      LookupSnapshotResponse response, long evaluatingVersion, String configurationHash) {
    if (!response.hasSnapshot()) {
      return result(
          TopLevelTargetsMatchStatus.MATCH_STATUS_NO_MATCH,
          String.format(
              "nothing has been uploaded for this Bazel and configuration %s at or before this"
                  + " workspace's base; analysing locally",
              configurationHash));
    }
    Snapshot snapshot = response.getSnapshot();
    if (snapshot.getMasterOrdinal() == evaluatingVersion) {
      return result(
          TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH,
          String.format("reading the upload of commit %s", snapshot.getCommit()));
    }
    if (snapshot.getMasterOrdinal() < evaluatingVersion) {
      return result(
          TopLevelTargetsMatchStatus.MATCH_STATUS_DIFFERENT_CL,
          String.format(
              "this workspace's base has not been uploaded; the newest upload for this"
                  + " configuration is commit %s, %d mainline commits earlier. Pass"
                  + " --canva_skycache_base=%s to read it",
              snapshot.getCommit(),
              evaluatingVersion - snapshot.getMasterOrdinal(),
              snapshot.getCommit()));
    }
    // The index answers only at or before the ordinal asked for; anything else is not ours to use.
    return result(
        TopLevelTargetsMatchStatus.MATCH_STATUS_FAILURE,
        String.format(
            "the snapshot index answered with commit %d, after this workspace's base %d",
            snapshot.getMasterOrdinal(), evaluatingVersion));
  }

  /**
   * Returns the install MD5 from {@code RemoteAnalysisCacheFactory}'s {@code <release>-<md5>}.
   *
   * <p>That is the MD5 the keys use, including a {@code
   * --experimental_analysis_cache_server_checksum_override}, which the directories' own does not
   * reflect. A release name can contain hyphens; the hex digest cannot.
   */
  @VisibleForTesting
  static String installMd5(String bazelVersion) {
    return bazelVersion.substring(bazelVersion.lastIndexOf('-') + 1);
  }

  /**
   * Returns the configuration half of the index key.
   *
   * <p>Stamping is in the keys but not the configuration checksum, so it is folded in here: a
   * stamped reader would miss every entry of an unstamped upload.
   */
  @VisibleForTesting
  static String configurationKey(String configurationHash, boolean useFakeStampData) {
    return useFakeStampData ? configurationHash : configurationHash + STAMPED_SUFFIX;
  }

  private static String describe(Exception e) {
    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
    return cause instanceof StatusRuntimeException ? Status.fromThrowable(cause).toString()
        : String.valueOf(cause.getMessage());
  }

  private static LookupTopLevelTargetsResult result(
      TopLevelTargetsMatchStatus status, String message) {
    return new LookupTopLevelTargetsResultImpl(status.getNumber(), message);
  }
}
