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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.collect.ImmutableSortedMap.toImmutableSortedMap;
import static com.google.common.util.concurrent.Futures.immediateFailedFuture;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.Comparator.naturalOrder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.concurrent.safeexecutor.SafeExecutor;
import com.google.devtools.build.lib.skyframe.serialization.Fingerprinter;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.UnexpandedReader;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FileDependenciesOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FutureFileDependencies;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FutureListingDependencies;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.FutureNestedDependencies;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.ListingDependenciesOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyDeserializer.NestedDependenciesOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FileOpMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileOpMatchResultTypes.FutureFileOpMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.FileSystemDependencies.FileOpDependency;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.FutureNestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.NestedMatchResultOrFuture;
import com.google.devtools.build.lib.skyframe.serialization.analysis.NestedMatchResultTypes.SourceMatch;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.util.Bucket;
import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * A {@link RemoteAnalysisCacheClient} that decides hits itself, from the invalidation data stored
 * with each value, instead of leaving the decision to an analysis cache service.
 *
 * <p>Every top-level entry is written with a header naming its file system dependencies, which
 * {@link FileDependencySerializer} uploaded alongside it. A lookup fetches the entry, reconstructs
 * those dependencies with {@link FileDependencyDeserializer}, and matches them against the reader's
 * changes with {@link VersionedChangesValidator}. An entry with a matching dependency, or with
 * dependency data that is missing, is answered as a miss.
 *
 * <p>The changes are the reader's view of how its workspace differs from the writer's:
 *
 * <ul>
 *   <li>The <b>validity horizon</b> is the version the writer evaluated at. Every entry it uploaded
 *       is known valid there.
 *   <li><b>Depot changes</b> are the files changed by each version after the horizon, up to and
 *       including the version the reader is synced to.
 *   <li><b>Client changes</b> are files whose content in the reader may differ from any version:
 *       local edits, and anything the writer had edited locally when it uploaded.
 * </ul>
 *
 * <p>All three are fixed for the lifetime of an instance, so an instance serves one command.
 * Matches are memoized across lookups, which is what makes checking a large graph affordable:
 * entries share most of their dependencies.
 */
public final class ValidatingAnalysisCacheClient implements RemoteAnalysisCacheClient {

  private static final String MISSING_DATA = "missing dependency data";

  private static final LookupResult ABSENT =
      new LookupResultImpl(new byte[0], MissReason.MISS_REASON_SKYVALUE_MISS.getNumber());
  private static final LookupResult INVALIDATED =
      new LookupResultImpl(new byte[0], MissReason.MISS_REASON_INVALIDATED.getNumber());

  /** A workspace-relative {@code path} changed by version {@code version}. */
  public record DepotChange(String path, int version) {}

  /**
   * Lookup outcomes.
   *
   * @param hits entries found and still valid
   * @param absent entries not in the store
   * @param invalidated entries found but invalidated by a change or by missing dependency data
   */
  public record ValidationStats(long hits, long absent, long invalidated) {}

  /**
   * Where entries and their invalidation data are read from, and how.
   *
   * @param store holds both the values and their invalidation data
   * @param executor runs dependency matching that waits on other matches
   * @param compressionService decompresses invalidation data the serializer compressed
   * @param fingerprinter computes the keys of invalidation data too long to store verbatim
   */
  public record Storage(
      FingerprintValueStore store,
      SafeExecutor executor,
      CompressionService compressionService,
      Fingerprinter fingerprinter) {}

  /**
   * Answers {@link #lookupTopLevelTargets} from wherever the writer recorded what it uploaded.
   *
   * <p>Entries are checked one by one either way. This only decides, ahead of the build, whether
   * there is anything at this client's version worth checking.
   */
  @FunctionalInterface
  public interface TopLevelTargetsLookup {
    LookupTopLevelTargetsResult lookup(
        long evaluatingVersion,
        String configurationHash,
        boolean useFakeStampData,
        String bazelVersion)
        throws ExecutionException, TimeoutException, InterruptedException;
  }

  private final FingerprintValueStore store;
  private final FileDependencyDeserializer deserializer;

  /** Fails if the changes could not be computed, and then every entry is a miss. */
  private final ListenableFuture<VersionedChangesValidator> validator;

  private final int validityHorizon;

  private final AtomicLong bytesSent = new AtomicLong();
  private final AtomicLong bytesReceived = new AtomicLong();
  private final AtomicLong requestsSent = new AtomicLong();
  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong absent = new AtomicLong();
  private final AtomicLong invalidated = new AtomicLong();
  private final AtomicInteger matchStatus =
      new AtomicInteger(TopLevelTargetsMatchStatus.MATCH_STATUS_UNSPECIFIED.getNumber());
  private final ConcurrentHashMap<String, LongAdder> invalidationReasons =
      new ConcurrentHashMap<>();
  private final AtomicReference<String> firstMissingData = new AtomicReference<>();
  @Nullable private volatile TopLevelTargetsLookup topLevelTargetsLookup;

  /**
   * Creates a client for one command.
   *
   * @param storage where entries and their invalidation data are read from
   * @param validityHorizon the version the writer evaluated at
   * @param depotChanges every file change in the versions after {@code validityHorizon}, up to the
   *     reader's version. Changes at or before the horizon are ignored.
   * @param clientChanges files that may differ from every version
   */
  public static ValidatingAnalysisCacheClient create(
      Storage storage,
      int validityHorizon,
      Iterable<DepotChange> depotChanges,
      Iterable<String> clientChanges) {
    checkArgument(
        validityHorizon >= 0 && validityHorizon < VersionedChanges.CLIENT_CHANGE,
        "validity horizon %s is out of range",
        validityHorizon);
    var changes = new VersionedChanges(clientChanges);
    for (DepotChange change : depotChanges) {
      checkArgument(
          change.version() < VersionedChanges.CLIENT_CHANGE, "version out of range: %s", change);
      if (change.version() > validityHorizon) {
        changes.registerFileChange(change.path(), change.version());
      }
    }
    return new ValidatingAnalysisCacheClient(
        storage,
        immediateFuture(new VersionedChangesValidator(storage.executor(), changes)),
        validityHorizon);
  }

  /**
   * Creates a client for one command whose client changes are still being computed.
   *
   * <p>Lookups wait for them, so the client can be handed out at once: the build waits only
   * briefly for a client, and without one gives up on the cache for the whole command. If the
   * changes fail, every lookup is a miss.
   *
   * @param clientChanges every file that may differ from the version {@code validityHorizon}
   *     names, with no depot changes beyond it
   */
  public static ValidatingAnalysisCacheClient create(
      Storage storage,
      int validityHorizon,
      ListenableFuture<? extends Iterable<String>> clientChanges) {
    checkArgument(
        validityHorizon >= 0 && validityHorizon < VersionedChanges.CLIENT_CHANGE,
        "validity horizon %s is out of range",
        validityHorizon);
    return new ValidatingAnalysisCacheClient(
        storage,
        Futures.transform(
            clientChanges,
            changes ->
                new VersionedChangesValidator(storage.executor(), new VersionedChanges(changes)),
            directExecutor()),
        validityHorizon);
  }

  private ValidatingAnalysisCacheClient(
      Storage storage,
      ListenableFuture<VersionedChangesValidator> validator,
      int validityHorizon) {
    this.store = storage.store();
    this.deserializer =
        new FileDependencyDeserializer(
            storage.executor(), storage.compressionService(), storage.fingerprinter());
    this.validator = validator;
    this.validityHorizon = validityHorizon;
  }

  @Override
  public ListenableFuture<LookupResult> lookup(byte[] key) {
    requestsSent.incrementAndGet();
    bytesSent.addAndGet(key.length);
    return Futures.transformAsync(
        Futures.catching(validator, Exception.class, unused -> null, directExecutor()),
        validator -> {
          if (validator == null) {
            invalidated.incrementAndGet();
            countReason("changes unavailable");
            return immediateFuture(INVALIDATED);
          }
          return lookup(key, validator);
        },
        directExecutor());
  }

  private ListenableFuture<LookupResult> lookup(byte[] key, VersionedChangesValidator validator) {
    ListenableFuture<byte[]> valueBytes;
    try {
      // Alone, where the store would otherwise send what the entry references too: whether the
      // entry is used is decided below, and its invalidation data and, if it is valid, its value
      // are asked for as they are read.
      PackedFingerprint fingerprint = PackedFingerprint.fromBytes(key);
      valueBytes =
          store instanceof UnexpandedReader unexpanded
              ? unexpanded.getUnexpanded(fingerprint)
              : store.get(fingerprint);
    } catch (IOException e) {
      return immediateFailedFuture(e);
    }
    return Futures.transformAsync(
        Futures.catchingAsync(
            valueBytes,
            Exception.class,
            e ->
                getRootCause(e) instanceof MissingFingerprintValueException
                    ? Futures.<byte[]>immediateFuture(null)
                    : Futures.<byte[]>immediateFailedFuture(e),
            directExecutor()),
        value -> validate(value, validator),
        directExecutor());
  }

  /**
   * Returns the value without its header if its dependencies are unchanged, or a miss otherwise.
   *
   * <p>An empty value is how the retriever expects a miss: a failed future is a serialization
   * error.
   */
  private ListenableFuture<LookupResult> validate(
      @Nullable byte[] value, VersionedChangesValidator validator) throws IOException {
    if (value == null || value.length == 0) {
      absent.incrementAndGet();
      return immediateFuture(ABSENT);
    }
    bytesReceived.addAndGet(value.length);

    // The framing is written by SelectedEntrySerializer and read the same way by SkyValueRetriever.
    CodedInputStream codedIn = CodedInputStream.newInstance(value);
    int ordinal = codedIn.readInt32();
    DataType dataType = DataType.forNumber(ordinal);
    if (dataType == null) {
      throw new IOException("unexpected data type with ordinal " + ordinal);
    }
    // Node entries report their nested dependencies' fingerprint, which the retriever keeps for
    // invalidating the deserialized value on a later build.
    byte[] invalidationFingerprint = null;
    ListenableFuture<Boolean> valid =
        switch (dataType) {
          case DATA_TYPE_EMPTY -> immediateFuture(true);
          case DATA_TYPE_FILE ->
              isUnchanged(
                  deserializer.getFileDependencies(codedIn.readString(), store), validator);
          case DATA_TYPE_LISTING ->
              isUnchanged(
                  deserializer.getListingDependencies(codedIn.readString(), store), validator);
          case DATA_TYPE_ANALYSIS_NODE -> {
            PackedFingerprint nested = PackedFingerprint.readFrom(codedIn);
            invalidationFingerprint = nested.toBytes();
            yield isUnchanged(deserializer.getNestedDependencies(nested, store), validator);
          }
          case DATA_TYPE_EXECUTION_NODE -> {
            // Analysis only. An execution value's output metadata describes files in the writer's
            // output base, and Bazel's executor has no analysis-only mode to keep them out of an
            // upload: a hit would skip the action and leave its outputs nowhere.
            countReason("execution value");
            yield immediateFuture(false);
          }
          default -> throw new IOException("unexpected data type " + dataType);
        };
    int headerBytes = codedIn.getTotalBytesRead();
    // The deserializer reads an absent record as missing data only when the store answers null.
    // Stores that fail the read instead reach here, and missing data is equally a miss. So is data
    // the deserializer rejects: the retriever would rethrow that and fail the build, where a miss
    // only costs evaluating the entry locally.
    valid =
        Futures.catching(
            valid,
            Exception.class,
            e -> {
              if (getRootCause(e) instanceof MissingFingerprintValueException missing) {
                countReason(MISSING_DATA);
                firstMissingData.compareAndSet(null, missing.getMessage());
              } else {
                countReason("unreadable dependency data: " + getRootCause(e).getClass().getName());
                firstMissingData.compareAndSet(null, getRootCause(e).toString());
              }
              return false;
            },
            directExecutor());
    byte[] fingerprint = invalidationFingerprint;
    return Futures.transform(
        valid,
        unchanged -> {
          if (!unchanged) {
            invalidated.incrementAndGet();
            return INVALIDATED;
          }
          hits.incrementAndGet();
          return new LookupResultImpl(
              Arrays.copyOfRange(value, headerBytes, value.length),
              fingerprint,
              MissReason.MISS_REASON_UNSPECIFIED.getNumber());
        },
        directExecutor());
  }

  private ListenableFuture<Boolean> isUnchanged(
      FileDependenciesOrFuture dependencies, VersionedChangesValidator validator) {
    return isUnchanged(
        switch (dependencies) {
          case FileDependencies done -> immediateFuture(done);
          case FutureFileDependencies future -> future;
        },
        validator);
  }

  private ListenableFuture<Boolean> isUnchanged(
      ListingDependenciesOrFuture dependencies, VersionedChangesValidator validator) {
    return isUnchanged(
        switch (dependencies) {
          case ListingDependencies done -> immediateFuture(done);
          case FutureListingDependencies future -> future;
        },
        validator);
  }

  private ListenableFuture<Boolean> isUnchanged(
      ListenableFuture<? extends FileOpDependency> dependency,
      VersionedChangesValidator validator) {
    return Futures.transformAsync(
        dependency,
        done -> {
          if (done.isMissingData()) {
            countReason(MISSING_DATA);
            return immediateFuture(false);
          }
          return Futures.transform(
              toFuture(validator.matches(done, validityHorizon)),
              result -> {
                if (result.isMatch()) {
                  countReason(result.getClass().getSimpleName());
                  return false;
                }
                return true;
              },
              directExecutor());
        },
        directExecutor());
  }

  /**
   * Matches nested dependencies.
   *
   * <p>A change to the entry's source file, which only execution consumes, does not invalidate
   * it: an analysis value does not depend on source contents.
   */
  private ListenableFuture<Boolean> isUnchanged(
      NestedDependenciesOrFuture dependencies, VersionedChangesValidator validator) {
    ListenableFuture<NestedDependencies> future =
        switch (dependencies) {
          case NestedDependencies done -> immediateFuture(done);
          case FutureNestedDependencies pending -> pending;
        };
    return Futures.transformAsync(
        future,
        done -> {
          if (done.isMissingData()) {
            countReason(MISSING_DATA);
            return immediateFuture(false);
          }
          return Futures.transform(
              toFuture(validator.matches(done, validityHorizon)),
              result -> {
                boolean valid =
                    switch (result) {
                      case NoMatch unused -> true;
                      case SourceMatch unused -> true;
                      default -> false;
                    };
                if (!valid) {
                  countReason(result.getClass().getSimpleName());
                }
                return valid;
              },
              directExecutor());
        },
        directExecutor());
  }

  private static ListenableFuture<FileOpMatchResult> toFuture(FileOpMatchResultOrFuture result) {
    return switch (result) {
      case FileOpMatchResult done -> immediateFuture(done);
      case FutureFileOpMatchResult future -> future;
    };
  }

  private static ListenableFuture<NestedMatchResult> toFuture(NestedMatchResultOrFuture result) {
    return switch (result) {
      case NestedMatchResult done -> immediateFuture(done);
      case FutureNestedMatchResult future -> future;
    };
  }

  public ValidationStats getValidationStats() {
    return new ValidationStats(hits.get(), absent.get(), invalidated.get());
  }

  /**
   * Returns how many entries each reason invalidated: missing dependency data, changes that could
   * not be computed, or the kind of match a change made.
   */
  public ImmutableSortedMap<String, Long> getInvalidationReasons() {
    return invalidationReasons.entrySet().stream()
        .collect(toImmutableSortedMap(naturalOrder(), Map.Entry::getKey, e -> e.getValue().sum()));
  }

  /** Returns the first dependency record found missing or unreadable, for diagnosis, or null. */
  @Nullable
  public String getFirstMissingData() {
    return firstMissingData.get();
  }

  private void countReason(String reason) {
    invalidationReasons.computeIfAbsent(reason, unused -> new LongAdder()).increment();
  }

  @Override
  public Stats getStats() {
    long sent = bytesSent.get();
    long received = bytesReceived.get();
    long requests = requestsSent.get();
    int status = matchStatus.get();
    return new Stats() {
      @Override
      public long bytesSent() {
        return sent;
      }

      @Override
      public long bytesReceived() {
        return received;
      }

      @Override
      public long requestsSent() {
        return requests;
      }

      @Override
      public long batches() {
        // Each lookup is its own read, so batches and requests are the same count.
        return requests;
      }

      @Override
      public List<Bucket> latencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public List<Bucket> batchLatencyMicros() {
        return ImmutableList.of();
      }

      @Override
      public int matchStatus() {
        return status;
      }
    };
  }

  /**
   * Has {@link #lookupTopLevelTargets} ask {@code lookup}. Call before handing the client out.
   *
   * <p>Without one, every query is a match: the entries themselves say whether they are valid.
   */
  public void setTopLevelTargetsLookup(@Nullable TopLevelTargetsLookup lookup) {
    this.topLevelTargetsLookup = lookup;
  }

  @Override
  public LookupTopLevelTargetsResult lookupTopLevelTargets(
      long evaluatingVersion,
      String configurationHash,
      boolean useFakeStampData,
      String bazelVersion)
      throws ExecutionException, TimeoutException, InterruptedException {
    TopLevelTargetsLookup lookup = topLevelTargetsLookup;
    LookupTopLevelTargetsResult result =
        lookup == null
            ? new LookupTopLevelTargetsResultImpl(
                TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH.getNumber(),
                "entries are checked against the workspace's changes as they are read")
            : lookup.lookup(evaluatingVersion, configurationHash, useFakeStampData, bazelVersion);
    matchStatus.set(result.status());
    return result;
  }

  @Override
  public void bailOutDueToMissingFingerprint() {
    matchStatus.set(TopLevelTargetsMatchStatus.MATCH_STATUS_MISSING_FINGERPRINT.getNumber());
  }
}
