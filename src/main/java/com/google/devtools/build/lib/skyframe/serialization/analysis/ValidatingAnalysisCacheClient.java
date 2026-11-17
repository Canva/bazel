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
import static com.google.devtools.build.lib.skyframe.serialization.analysis.FileDependencyKeySupport.MAX_KEY_LENGTH;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.naturalOrder;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
import com.google.protobuf.ByteString;
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
import java.util.function.BooleanSupplier;
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
 *
 * <p>Affordable is still the dependency data of every entry read, most of what a reader with a
 * few changed files downloads. A store that holds the references between entries can be asked
 * the question the other way round, once: which entries do these changes reach ({@link
 * AffectedLookup}). With that answer an entry is a miss if its dependencies are among them and a
 * hit otherwise, and no dependency data is read at all.
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

  /**
   * The dependency entries a reader's changes reach, by the keys they are stored under.
   *
   * @param analysis those a change reaches through the files and listings something was analysed
   *     from. An entry of either kind that depends on one is invalid.
   * @param contentsOnly those a change reaches only as the contents of a source file. An analysis
   *     value that depends on one is still valid, and what executing gave is not.
   */
  public record AffectedEntries(
      ImmutableSet<ByteString> analysis, ImmutableSet<ByteString> contentsOnly) {}

  /**
   * Asks the store which dependency entries some changes reach.
   *
   * <p>The future fails if the store cannot say, and entries are then checked against their
   * dependency data as they are read. An answer has to name every entry a reader would find
   * matching: an entry it leaves out is used.
   */
  @FunctionalInterface
  public interface AffectedLookup {
    ListenableFuture<AffectedEntries> lookup(ClientChanges changes);
  }

  /**
   * The longest changed path {@link AffectedLookup} is asked about. The entry of a longer one may
   * be stored under a fingerprint of its key, which a store cannot match to the path: the key is
   * the path behind a version of up to twelve characters and a delimiter.
   */
  private static final int MAX_AFFECTED_PATH_LENGTH = MAX_KEY_LENGTH - 13;

  private final FingerprintValueStore store;
  private final FileDependencyDeserializer deserializer;
  private final Fingerprinter fingerprinter;

  /** Why the {@link AffectedLookup} failed, if it did. */
  private AtomicReference<String> affectedFailure = new AtomicReference<>();

  /** Fails if the changes could not be computed, and then every entry is a miss. */
  private final ListenableFuture<Checker> checker;

  /**
   * What entries are checked against.
   *
   * @param noChanges whether the reader's workspace is exactly the writer's, so that nothing it
   *     finds can be invalidated by a change
   * @param affected the dependency entries the changes reach, if the store said. Entries are then
   *     checked against this and not against {@code validator}.
   */
  private record Checker(
      VersionedChangesValidator validator, boolean noChanges, @Nullable AffectedEntries affected) {
    private static Checker of(Storage storage, VersionedChanges changes) {
      return new Checker(
          new VersionedChangesValidator(storage.executor(), changes),
          changes.isEmpty(),
          /* affected= */ null);
    }

    private Checker withAffected(AffectedEntries affected) {
      return new Checker(validator, noChanges, affected);
    }

    /** Whether nothing an entry is read for depends on reading its dependency data first. */
    private boolean readsNoDependencyData() {
      return noChanges || affected != null;
    }
  }

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

  /** Says whether to decline a lookup because the store has too many reads in flight. */
  private volatile BooleanSupplier saturation = () -> false;

  private static final LookupResult SATURATED =
      new LookupResultImpl(new byte[0], MissReason.MISS_REASON_CACHE_SATURATED.getNumber());

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
        storage, immediateFuture(Checker.of(storage, changes)), validityHorizon);
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
            changes -> Checker.of(storage, new VersionedChanges(changes)),
            directExecutor()),
        validityHorizon);
  }

  /**
   * How a reader's workspace differs from the version it reads at.
   *
   * @param paths every file and directory that differs: added, deleted, edited or changed in type
   * @param listings every directory whose listing differs: one that gained or lost an entry, or
   *     has an entry that changed type. An edit in place changes no listing.
   */
  public record ClientChanges(ImmutableSet<String> paths, ImmutableSet<String> listings) {}

  /**
   * As {@link #create(Storage, int, ListenableFuture)}, for client changes that say which listings
   * changed. Without that, every changed file counts as a change to the listing of its directory.
   */
  public static ValidatingAnalysisCacheClient createWithListings(
      Storage storage, int validityHorizon, ListenableFuture<ClientChanges> clientChanges) {
    return createWithListings(storage, validityHorizon, clientChanges, /* affectedLookup= */ null);
  }

  /**
   * As {@link #createWithListings(Storage, int, ListenableFuture)}, asking {@code affectedLookup}
   * which entries the changes reach if there are any and it is not null.
   */
  public static ValidatingAnalysisCacheClient createWithListings(
      Storage storage,
      int validityHorizon,
      ListenableFuture<ClientChanges> clientChanges,
      @Nullable AffectedLookup affectedLookup) {
    checkArgument(
        validityHorizon >= 0 && validityHorizon < VersionedChanges.CLIENT_CHANGE,
        "validity horizon %s is out of range",
        validityHorizon);
    var affectedFailure = new AtomicReference<String>();
    var client =
        new ValidatingAnalysisCacheClient(
            storage,
            Futures.transformAsync(
                clientChanges,
                changes -> {
                  Checker checker =
                      Checker.of(
                          storage, new VersionedChanges(changes.paths(), changes.listings()));
                  if (affectedLookup == null || checker.noChanges() || !canAskAbout(changes)) {
                    return immediateFuture(checker);
                  }
                  return Futures.catching(
                      Futures.transform(
                          affectedLookup.lookup(changes), checker::withAffected, directExecutor()),
                      Exception.class,
                      e -> {
                        affectedFailure.set(getRootCause(e).toString());
                        return checker;
                      },
                      directExecutor());
                },
                directExecutor()),
            validityHorizon);
    client.affectedFailure = affectedFailure;
    return client;
  }

  private static boolean canAskAbout(ClientChanges changes) {
    for (String path : changes.paths()) {
      if (path.length() > MAX_AFFECTED_PATH_LENGTH) {
        return false;
      }
    }
    for (String path : changes.listings()) {
      if (path.length() > MAX_AFFECTED_PATH_LENGTH) {
        return false;
      }
    }
    return true;
  }

  private ValidatingAnalysisCacheClient(
      Storage storage, ListenableFuture<Checker> checker, int validityHorizon) {
    this.store = storage.store();
    this.deserializer =
        new FileDependencyDeserializer(
            storage.executor(), storage.compressionService(), storage.fingerprinter());
    this.fingerprinter = storage.fingerprinter();
    this.checker = checker;
    this.validityHorizon = validityHorizon;
  }

  /**
   * Has lookups answered as misses while {@code saturation} says the store is saturated.
   *
   * <p>Declining here, before anything is read, costs analysing the one target locally. Declining
   * a read made on a lookup's behalf, of invalidation data or of a nested value, would fail every
   * entry that shares what was not read.
   */
  public void setSaturation(BooleanSupplier saturation) {
    this.saturation = saturation;
  }

  @Override
  public ListenableFuture<LookupResult> lookup(byte[] key) {
    if (saturation.getAsBoolean()) {
      absent.incrementAndGet();
      return immediateFuture(SATURATED);
    }
    requestsSent.incrementAndGet();
    bytesSent.addAndGet(key.length);
    return Futures.transformAsync(
        Futures.catching(checker, Exception.class, unused -> null, directExecutor()),
        checker -> {
          if (checker == null) {
            invalidated.incrementAndGet();
            countReason("changes unavailable");
            return immediateFuture(INVALIDATED);
          }
          return lookup(key, checker);
        },
        directExecutor());
  }

  private ListenableFuture<LookupResult> lookup(byte[] key, Checker checker) {
    ListenableFuture<byte[]> valueBytes;
    try {
      PackedFingerprint fingerprint = PackedFingerprint.fromBytes(key);
      // A store that follows references sends what the entry references along with it. That is
      // wanted if the entry is going to be used, and with no changes to check it against it is.
      // Otherwise the entry is asked for alone: whether it is used is decided below, and its
      // invalidation data and, if it is valid, its value are asked for as they are read. That
      // costs a kept entry two more round trips, and spares a rejected one its whole value.
      valueBytes =
          !checker.readsNoDependencyData() && store instanceof UnexpandedReader unexpanded
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
        value -> validate(value, checker),
        directExecutor());
  }

  /**
   * Returns the value without its header if its dependencies are unchanged, or a miss otherwise.
   *
   * <p>An empty value is how the retriever expects a miss: a failed future is a serialization
   * error.
   */
  private ListenableFuture<LookupResult> validate(@Nullable byte[] value, Checker checker)
      throws IOException {
    VersionedChangesValidator validator = checker.validator();
    // With no changes there is nothing for a dependency to have changed to: the entry was written
    // from this workspace's exact contents. Its dependency data is then not read at all, which
    // for a reader at the commit that was uploaded is most of what it would otherwise fetch
    // besides the values themselves.
    boolean noChanges = checker.noChanges();
    // With the entries the changes reach in hand, an entry is checked against those alone.
    AffectedEntries affected = checker.affected();
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
          case DATA_TYPE_FILE -> {
            String fileKey = codedIn.readString();
            yield noChanges
                ? immediateFuture(true)
                : affected != null
                    ? isUnaffected(affected, storedKey(fileKey), /* contentsMatter= */ true)
                    : isUnchanged(deserializer.getFileDependencies(fileKey, store), validator);
          }
          case DATA_TYPE_LISTING -> {
            String listingKey = codedIn.readString();
            yield noChanges
                ? immediateFuture(true)
                : affected != null
                    ? isUnaffected(affected, storedKey(listingKey), /* contentsMatter= */ true)
                    : isUnchanged(
                        deserializer.getListingDependencies(listingKey, store), validator);
          }
          case DATA_TYPE_ANALYSIS_NODE -> {
            PackedFingerprint nested = PackedFingerprint.readFrom(codedIn);
            invalidationFingerprint = nested.toBytes();
            yield noChanges
                ? immediateFuture(true)
                : affected != null
                    ? isUnaffected(
                        affected,
                        ByteString.copyFrom(invalidationFingerprint),
                        /* contentsMatter= */ false)
                    : isUnchanged(
                        deserializer.getNestedDependencies(nested, store),
                        validator,
                        /* sourcesMatter= */ false);
          }
          case DATA_TYPE_EXECUTION_NODE -> {
            if (!ExecutionValueCaching.isEnabled()) {
              // Analysis only: see ExecutionValueCaching.
              countReason("execution value");
              yield immediateFuture(false);
            }
            // An action's result also depends on the contents of the source files its owner and
            // the owner's dependencies declare, so a change to one of those invalidates it too.
            PackedFingerprint nested = PackedFingerprint.readFrom(codedIn);
            invalidationFingerprint = nested.toBytes();
            yield noChanges
                ? immediateFuture(true)
                : affected != null
                    ? isUnaffected(
                        affected,
                        ByteString.copyFrom(invalidationFingerprint),
                        /* contentsMatter= */ true)
                    : isUnchanged(
                        deserializer.getNestedDependencies(nested, store),
                        validator,
                        /* sourcesMatter= */ true);
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

  /**
   * Whether the dependency entry stored under {@code key} is none of those the changes reach.
   *
   * @param contentsMatter whether a change that reaches it only as the contents of a source file
   *     counts, which it does for everything but an analysis value
   */
  private ListenableFuture<Boolean> isUnaffected(
      AffectedEntries affected, ByteString key, boolean contentsMatter) {
    if (affected.analysis().contains(key)) {
      countReason("AffectedAnalysis");
      return immediateFuture(false);
    }
    if (contentsMatter && affected.contentsOnly().contains(key)) {
      countReason("AffectedContents");
      return immediateFuture(false);
    }
    return immediateFuture(true);
  }

  /** The key the entry of a file or a listing is stored under, as its writer derived it. */
  private ByteString storedKey(String cacheKey) {
    byte[] bytes = cacheKey.getBytes(UTF_8);
    return cacheKey.length() > MAX_KEY_LENGTH
        ? ByteString.copyFrom(fingerprinter.fingerprint(bytes).toBytes())
        : ByteString.copyFrom(bytes);
  }

  /**
   * Returns how many entries the store said the changes reach, or why it did not say. Null before
   * the changes are known, and if the store was not asked.
   */
  @Nullable
  public String getAffectedSummary() {
    if (!checker.isDone()) {
      return null;
    }
    Checker done;
    try {
      done = Futures.getDone(checker);
    } catch (ExecutionException | RuntimeException e) {
      return null;
    }
    if (done.affected() != null) {
      return String.format(
          "%d dependency entries reached by the changes, %d more by source contents only",
          done.affected().analysis().size(), done.affected().contentsOnly().size());
    }
    String failure = affectedFailure.get();
    return failure == null ? null : "not asked for or not answered: " + failure;
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
      NestedDependenciesOrFuture dependencies,
      VersionedChangesValidator validator,
      boolean sourcesMatter) {
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
                      case SourceMatch unused -> !sourcesMatter;
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
