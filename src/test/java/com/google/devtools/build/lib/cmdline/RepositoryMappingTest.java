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

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link RepositoryMapping}. */
@RunWith(JUnit4.class)
public final class RepositoryMappingTest {

  @Test
  public void neverFallback() throws Exception {
    RepositoryMapping mapping =
        RepositoryMapping.create(
            ImmutableMap.of("A", RepositoryName.create("com_foo_bar_a")),
            RepositoryName.create("fake_owner_repo"));
    assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("com_foo_bar_a"));
    assertThat(mapping.get("B"))
        .isEqualTo(
            RepositoryName.create("B").toNonVisible(RepositoryName.create("fake_owner_repo")));
  }

  @Test
  public void additionalMappings_basic() throws Exception {
    RepositoryMapping mapping =
        RepositoryMapping.create(
                ImmutableMap.of("A", RepositoryName.create("com_foo_bar_a")),
                RepositoryName.create("fake_owner_repo"))
            .withAdditionalMappings(ImmutableMap.of("B", RepositoryName.create("com_foo_bar_b")));
    assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("com_foo_bar_a"));
    assertThat(mapping.get("B")).isEqualTo(RepositoryName.create("com_foo_bar_b"));
    assertThat(mapping.get("C"))
        .isEqualTo(
            RepositoryName.create("C").toNonVisible(RepositoryName.create("fake_owner_repo")));
  }

  @Test
  public void additionalMappings_precedence() throws Exception {
    RepositoryMapping mapping =
        RepositoryMapping.create(
                ImmutableMap.of("A", RepositoryName.create("A1")), RepositoryName.MAIN)
            .withAdditionalMappings(ImmutableMap.of("A", RepositoryName.create("A2")));
    assertThat(mapping.get("A")).isEqualTo(RepositoryName.create("A1"));
  }

  @Test
  public void unknownRepoDidYouMean() throws LabelSyntaxException {
    RepositoryMapping mapping =
        RepositoryMapping.create(
            ImmutableMap.of("foo", RepositoryName.create("foo_internal")), RepositoryName.MAIN);
    assertThat(mapping.get("boo").getNameWithAt())
        .isEqualTo("@@[unknown repo 'boo' requested from @@ (did you mean 'foo'?)]");
  }

  @Test
  public void codec_roundTripsBothVariants() throws Exception {
    RepositoryMapping mapping =
        RepositoryMapping.create(
            ImmutableMap.of(
                "",
                RepositoryName.MAIN,
                "rules_go",
                RepositoryName.createUnvalidated("rules_go+")),
            RepositoryName.createUnvalidated("some_module+"));
    new SerializationTester(mapping, mapping.withCachedInverseMap(), RepositoryMapping.EMPTY)
        .addCodec(RepositoryMapping.deferredCodec())
        // The entries are a shared value, which only a context with a value store can write.
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .setVerificationFunction(
            (RepositoryMapping original, RepositoryMapping deserialized) -> {
              assertThat(deserialized).isEqualTo(original);
              assertThat(deserialized.hasCachedInverse()).isEqualTo(original.hasCachedInverse());
              assertThat(deserialized.getInverse(RepositoryName.createUnvalidated("rules_go+")))
                  .isEqualTo(original.getInverse(RepositoryName.createUnvalidated("rules_go+")));
            })
        .runTests();
  }

  @Test
  public void codec_mappingsOverOneEntriesMap_shareItWhenReadBack() throws Exception {
    // Every repository a module extension generates sees the same repositories, so thousands of
    // mappings are built over one map, differing only in the repository they are for.
    ImmutableMap<String, RepositoryName> entries =
        ImmutableMap.of(
            "", RepositoryName.MAIN, "rules_go", RepositoryName.createUnvalidated("rules_go+"));
    var first = RepositoryMapping.create(entries, RepositoryName.createUnvalidated("first+"));
    var second = RepositoryMapping.create(entries, RepositoryName.createUnvalidated("second+"));
    assertThat(first.entries()).isSameInstanceAs(second.entries());

    // A list of one subject, which is itself the list of the two mappings.
    new SerializationTester(ImmutableList.of(ImmutableList.of(first, second)))
        .addCodec(RepositoryMapping.deferredCodec())
        .makeMemoizingAndAllowFutureBlocking(/* allowFutureBlocking= */ true)
        .setVerificationFunction(
            (ImmutableList<RepositoryMapping> original,
                ImmutableList<RepositoryMapping> deserialized) -> {
              assertThat(deserialized).isEqualTo(original);
              assertThat(deserialized.get(0).entries())
                  .isSameInstanceAs(deserialized.get(1).entries());
              assertThat(deserialized.get(0).hashCode()).isEqualTo(original.get(0).hashCode());
            })
        .runTests();
  }
}
