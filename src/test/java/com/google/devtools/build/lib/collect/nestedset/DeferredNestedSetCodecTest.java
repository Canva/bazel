// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructure;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.compress.CompressionService;
import com.google.devtools.build.lib.compress.CompressionServiceImpl;
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService;
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs;
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import com.google.protobuf.ByteString;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class DeferredNestedSetCodecTest {

  private static final CompressionService COMPRESSION_SERVICE = new CompressionServiceImpl();

  @Test
  public void empty() throws Exception {
    new SerializationTester(
            Order.STABLE_ORDER.emptySet(),
            Order.COMPILE_ORDER.emptySet(),
            Order.LINK_ORDER.emptySet(),
            Order.NAIVE_LINK_ORDER.emptySet())
        .addCodec(new DeferredNestedSetCodec())
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .runTests();
  }

  @Test
  public void singleton() throws Exception {
    new SerializationTester(
            NestedSetBuilder.stableOrder().add("A").build(),
            NestedSetBuilder.compileOrder().add("B").build(),
            NestedSetBuilder.linkOrder().add("C").build(),
            NestedSetBuilder.naiveLinkOrder().add("D").build())
        .addCodec(new DeferredNestedSetCodec())
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .setVerificationFunction(DeferredNestedSetCodecTest::verifyUsingShallowEquals)
        .runTests();
  }

  @Test
  public void array() throws Exception {
    new SerializationTester(
            NestedSetBuilder.stableOrder().addAll(ImmutableList.of(1, 2, 3)).build(),
            NestedSetBuilder.compileOrder().addAll(ImmutableList.of("A", "B", "C")).build(),
            NestedSetBuilder.linkOrder().addAll(ImmutableList.of(5.56, 3.14, 10, 20)).build(),
            NestedSetBuilder.naiveLinkOrder()
                .addAll(ImmutableList.of("one", "two", "three", "four", "five"))
                .build())
        .addCodec(new DeferredNestedSetCodec())
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .setVerificationFunction(DeferredNestedSetCodecTest::verifyUsingShallowEquals)
        .runTests();
  }

  @Test
  public void diamond() throws Exception {
    var root = NestedSetBuilder.stableOrder().addAll(ImmutableList.of(1, 2)).build();
    var left = NestedSetBuilder.stableOrder().add("left").addTransitive(root).build();
    var right = NestedSetBuilder.stableOrder().add("right").addTransitive(root).build();
    var top =
        NestedSetBuilder.stableOrder()
            .addAll(ImmutableList.of("this", "is", "the", "top"))
            .addTransitive(left)
            .addTransitive(right)
            .build();

    var fingerprintValueService = FingerprintValueService.createForTesting();
    var codecs =
        new ObjectCodecs(AutoRegistry.get().getBuilder().add(new DeferredNestedSetCodec()).build());

    SerializationResult<ByteString> serialized =
        codecs.serializeMemoizedAndBlocking(COMPRESSION_SERVICE, fingerprintValueService, top);
    ListenableFuture<?> futureToBlockWritesOn = serialized.getFutureToBlockWritesOn();
    if (futureToBlockWritesOn != null) {
      var unused = futureToBlockWritesOn.get();
    }
    ByteString bytes = serialized.getObject();

    NestedSet<?> deserialized =
        (NestedSet<?>)
            codecs.deserializeMemoizedAndBlocking(
                COMPRESSION_SERVICE, fingerprintValueService, bytes);
    // Since dumpStructure doesn't perform equivalence reduction, equivalence here means the diamond
    // reference structure was preserved by deserialization.
    assertThat(dumpStructure(top)).isEqualTo(dumpStructure(deserialized));
  }

  /** Serializes and deserializes {@code value} as a value of its own, as a Skyframe value is. */
  private static Object roundTrip(
      ObjectCodecs codecs, FingerprintValueService fingerprintValueService, Object value)
      throws Exception {
    SerializationResult<ByteString> serialized =
        codecs.serializeMemoizedAndBlocking(COMPRESSION_SERVICE, fingerprintValueService, value);
    ListenableFuture<?> futureToBlockWritesOn = serialized.getFutureToBlockWritesOn();
    if (futureToBlockWritesOn != null) {
      var unused = futureToBlockWritesOn.get();
    }
    return codecs.deserializeMemoizedAndBlocking(
        COMPRESSION_SERVICE, fingerprintValueService, serialized.getObject());
  }

  private static ObjectCodecs analysisCachingCodecs() {
    return new ObjectCodecs(
        AutoRegistry.get()
            .getBuilder()
            .add(new DeferredNestedSetCodec())
            .add(new DepsetCodec())
            .build());
  }

  @Test
  public void equalSingletons_readFromSeparateValues_areOneSet() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();
    ObjectCodecs codecs = analysisCachingCodecs();
    // A singleton's element is written inside the set, so each value holding the set brings its
    // own copy of the element. The lists stand for two such values.
    NestedSet<ImmutableList<String>> set =
        NestedSetBuilder.<ImmutableList<String>>stableOrder()
            .add(ImmutableList.of("only", "element"))
            .build();

    var first = (ImmutableList<?>) roundTrip(codecs, fingerprintValueService, ImmutableList.of(set, "a"));
    var second = (ImmutableList<?>) roundTrip(codecs, fingerprintValueService, ImmutableList.of(set, "b"));

    assertThat(first.get(0)).isSameInstanceAs(second.get(0));
    assertThat(((NestedSet<?>) first.get(0)).toList())
        .containsExactly(ImmutableList.of("only", "element"));
  }

  @Test
  public void singletonsOfDifferentOrder_stayDifferentSets() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();
    ObjectCodecs codecs = analysisCachingCodecs();

    var stable =
        (NestedSet<?>)
            roundTrip(
                codecs, fingerprintValueService, NestedSetBuilder.stableOrder().add("x").build());
    var link =
        (NestedSet<?>)
            roundTrip(
                codecs, fingerprintValueService, NestedSetBuilder.linkOrder().add("x").build());

    assertThat(stable.getOrder()).isEqualTo(Order.STABLE_ORDER);
    assertThat(link.getOrder()).isEqualTo(Order.LINK_ORDER);
  }

  @Test
  public void depsets_readFromSeparateValues_areOneDepsetPerSet() throws Exception {
    var fingerprintValueService = FingerprintValueService.createForTesting();
    ObjectCodecs codecs = analysisCachingCodecs();
    Depset single = Depset.of(String.class, NestedSetBuilder.<String>stableOrder().add("x").build());
    Depset several =
        Depset.of(
            String.class,
            NestedSetBuilder.<String>stableOrder().addAll(ImmutableList.of("x", "y", "z")).build());
    Depset empty = Depset.of(String.class, NestedSetBuilder.<String>emptySet(Order.STABLE_ORDER));

    var first =
        (ImmutableList<?>)
            roundTrip(codecs, fingerprintValueService, ImmutableList.of(single, several, empty, 1));
    var second =
        (ImmutableList<?>)
            roundTrip(codecs, fingerprintValueService, ImmutableList.of(single, several, empty, 2));

    // A depset compares by identity, so these being the same objects is what makes two structs
    // that hold them equal.
    assertThat(first.get(0)).isSameInstanceAs(second.get(0));
    assertThat(first.get(1)).isSameInstanceAs(second.get(1));
    assertThat(first.get(2)).isSameInstanceAs(second.get(2));
    assertThat(first.get(0)).isNotSameInstanceAs(first.get(1));
    assertThat(((Depset) first.get(1)).getSet().toList()).containsExactly("x", "y", "z").inOrder();
    assertThat(((Depset) first.get(1)).getElementClass()).isEqualTo(String.class);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static void verifyUsingShallowEquals(NestedSet original, NestedSet deserialized) {
    assertThat(original.shallowEquals(deserialized)).isTrue();
  }
}
