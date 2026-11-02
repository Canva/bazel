// Copyright 2021 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.cmdline;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import net.starlark.java.spelling.SpellChecker;

/**
 * Stores the mapping from apparent repo name to canonical repo name, from the viewpoint of a
 * context repo.
 *
 * <p>This class must not implement {@link net.starlark.java.eval.StarlarkValue} since instances of
 * this class are used as markers by {@link
 * com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine}.
 */
public class RepositoryMapping {
  /* An empty repo mapping with the main repo as the context repo. */
  public static final RepositoryMapping EMPTY =
      create(ImmutableMap.of("", RepositoryName.MAIN), RepositoryName.MAIN);

  private final ImmutableMap<String, RepositoryName> entries;
  private final RepositoryName contextRepo;
  // entries has an expensive hashCode() method that isn't cached.
  private final int hashCode;

  private RepositoryMapping(
      Map<String, RepositoryName> entries, int entriesHashCode, RepositoryName contextRepo) {
    this.entries = ImmutableMap.copyOf(entries);
    this.contextRepo = contextRepo;
    this.hashCode = 31 * entriesHashCode + contextRepo.hashCode();
  }

  /** Returns all the entries in this repo mapping. */
  public final ImmutableMap<String, RepositoryName> entries() {
    return entries;
  }

  /**
   * The context repo of this repository mapping. It is for providing useful debug information when
   * repository mapping fails due to enforcing strict dependency.
   */
  public final RepositoryName contextRepo() {
    return contextRepo;
  }

  @Override
  public boolean equals(Object o) {
    return this == o
        || (o instanceof RepositoryMapping that
            && Objects.equals(entries, that.entries)
            && Objects.equals(contextRepo, that.contextRepo));
  }

  @Override
  public int hashCode() {
    return hashCode;
  }

  @Override
  public String toString() {
    return String.format("RepositoryMapping{entries=%s, contextRepo=%s}", entries, contextRepo);
  }

  public static RepositoryMapping create(
      ImmutableMap<String, RepositoryName> entries, RepositoryName contextRepo) {
    return createWithKnownEntriesHashCode(
        entries, entries.hashCode(), Preconditions.checkNotNull(contextRepo));
  }

  /**
   * Use this over {@link #create} if the {@link ImmutableMap#hashCode()} of the entries map is
   * already known.
   *
   * <p>Avoids repeated recomputation for RepositoryMapping instances with identical entries maps.
   */
  public static RepositoryMapping createWithKnownEntriesHashCode(
      ImmutableMap<String, RepositoryName> entries,
      int entriesHashCode,
      RepositoryName contextRepo) {
    return new RepositoryMapping(
        Preconditions.checkNotNull(entries),
        entriesHashCode,
        Preconditions.checkNotNull(contextRepo));
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence.
   */
  public RepositoryMapping withAdditionalMappings(
      ImmutableMap<String, RepositoryName> additionalMappings) {
    return create(
        ImmutableMap.<String, RepositoryName>builderWithExpectedSize(
                entries().size() + additionalMappings.size())
            .putAll(additionalMappings)
            .putAll(entries())
            .buildKeepingLast(),
        contextRepo());
  }

  /**
   * Create a new {@link RepositoryMapping} instance based on existing repo mappings and given
   * additional mappings. If there are conflicts, existing mappings will take precedence. The owner
   * repo of the given additional mappings is ignored.
   */
  public RepositoryMapping withAdditionalMappings(RepositoryMapping additionalMappings) {
    return withAdditionalMappings(
        (additionalMappings == null) ? ImmutableMap.of() : additionalMappings.entries());
  }

  /**
   * Returns the canonical repository name associated with the given apparent repo name. The
   * provided apparent repo name is assumed to be valid.
   */
  public RepositoryName get(String preMappingName) {
    RepositoryName canonicalRepoName = entries().get(preMappingName);
    if (canonicalRepoName != null) {
      return canonicalRepoName;
    }
    return RepositoryName.createUnvalidated(preMappingName)
        .toNonVisible(contextRepo(), SpellChecker.didYouMean(preMappingName, entries().keySet()));
  }

  /**
   * Returns the first apparent name in this mapping that maps to the given canonical name, if any.
   */
  public Optional<String> getInverse(RepositoryName postMappingName) {
    return entries().entrySet().stream()
        .filter(e -> e.getValue().equals(postMappingName))
        .map(Entry::getKey)
        .findFirst();
  }

  /**
   * Returns a new {@link RepositoryMapping} instance with identical contents, except that the
   * inverse mapping is cached, causing {@link #getInverse} to be much more efficient. This is
   * particularly important for the main repo mapping, as it's often used to generate display-form
   * labels ({@link Label#getDisplayForm}).
   */
  public RepositoryMapping withCachedInverseMap() {
    var inverse = Maps.<RepositoryName, String>newHashMapWithExpectedSize(entries.size());
    for (Map.Entry<String, RepositoryName> entry : entries.entrySet()) {
      inverse.putIfAbsent(entry.getValue(), entry.getKey());
    }
    var inverseCopy = ImmutableMap.copyOf(inverse);
    return new RepositoryMapping(entries, entries.hashCode(), contextRepo) {
      @Override
      public Optional<String> getInverse(RepositoryName postMappingName) {
        return Optional.ofNullable(inverseCopy.get(postMappingName));
      }

      @Override
      boolean hasCachedInverse() {
        return true;
      }
    };
  }

  /** Whether this instance came from {@link #withCachedInverseMap}. */
  boolean hasCachedInverse() {
    return false;
  }

  /**
   * Returns a codec for value sharing.
   *
   * <p>Without one, a mapping is serialized field by field wherever it is reachable, and every
   * package's mapping is reachable from every label, action owner and package in it: the main
   * repository's has tens of thousands of entries. Each cached value then carried its own copy, and
   * a reader of a large build held one per value. Shared by fingerprint, a mapping is stored once
   * and deserialized once, and mappings with equal entries share one entries map, as they do when
   * built locally.
   *
   * <p>Covers {@link #withCachedInverseMap}'s subclass too, since the codec registry looks up the
   * class hierarchy, and rebuilds that variant.
   */
  public static DeferredObjectCodec<RepositoryMapping> deferredCodec() {
    return RepositoryMappingCodec.INSTANCE;
  }

  private static final class RepositoryMappingCodec
      extends DeferredObjectCodec<RepositoryMapping> {
    private static final RepositoryMappingCodec INSTANCE = new RepositoryMappingCodec();

    /**
     * Mappings for many context repos share one entries map when built locally, the main
     * repository's tens of thousands of entries among them. Each mapping has its own fingerprint, so
     * without this every one deserialized would bring its own copy.
     */
    private static final Interner<ImmutableMap<String, RepositoryName>> entriesInterner =
        BlazeInterners.newWeakInterner();

    @Override
    public boolean autoRegister() {
      return false;
    }

    @Override
    public Class<RepositoryMapping> getEncodedClass() {
      return RepositoryMapping.class;
    }

    @Override
    public void serialize(
        SerializationContext context, RepositoryMapping obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      codedOut.writeStringNoTag(obj.contextRepo().getName());
      codedOut.writeBoolNoTag(obj.hasCachedInverse());
      codedOut.writeInt32NoTag(obj.entries().size());
      for (Map.Entry<String, RepositoryName> entry : obj.entries().entrySet()) {
        codedOut.writeStringNoTag(entry.getKey());
        codedOut.writeStringNoTag(entry.getValue().getName());
      }
    }

    @Override
    public DeferredValue<RepositoryMapping> deserializeDeferred(
        AsyncDeserializationContext context, CodedInputStream codedIn)
        throws SerializationException, IOException {
      RepositoryName contextRepo = RepositoryName.createUnvalidated(codedIn.readString());
      boolean cachedInverse = codedIn.readBool();
      int size = codedIn.readInt32();
      var entries = ImmutableMap.<String, RepositoryName>builderWithExpectedSize(size);
      for (int i = 0; i < size; i++) {
        entries.put(codedIn.readString(), RepositoryName.createUnvalidated(codedIn.readString()));
      }
      RepositoryMapping mapping =
          create(entriesInterner.intern(entries.buildOrThrow()), contextRepo);
      RepositoryMapping value = cachedInverse ? mapping.withCachedInverseMap() : mapping;
      return () -> value;
    }
  }
}
