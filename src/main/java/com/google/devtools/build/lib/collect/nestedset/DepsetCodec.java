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

import com.google.common.collect.MapMaker;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nullable;

/**
 * Reads a {@link Depset} back as the one object for its {@link NestedSet}.
 *
 * <p>A depset compares by identity. A struct holding one compares by its fields, and a depset of
 * such structs keeps one of each. The writer may have put the same struct into several values
 * that are serialized separately; if each copy came back with a depset object of its own the
 * copies would no longer compare equal, and the reader would keep them all where the writer kept
 * one. {@link DeferredNestedSetCodec} already returns one {@code NestedSet} for the same contents,
 * so one wrapper per set is one wrapper per contents.
 *
 * <p>For the remote analysis cache only, alongside {@link DeferredNestedSetCodec}.
 */
public final class DepsetCodec extends DeferredObjectCodec<Depset> {

  /** Weak on both sides: a depset references its set, and nothing here may keep either alive. */
  private static final ConcurrentMap<NestedSet<?>, Depset> deserializedBySet =
      new MapMaker().weakKeys().weakValues().makeMap();

  @Override
  public boolean autoRegister() {
    return false;
  }

  @Override
  public Class<Depset> getEncodedClass() {
    return Depset.class;
  }

  @Override
  public void serialize(SerializationContext context, Depset depset, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    context.serialize(depset.getRawElementClass(), codedOut);
    context.serialize(depset.getSet(), codedOut);
  }

  @Override
  public DeferredValue<Depset> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    var builder = new Builder();
    context.deserialize(codedIn, builder, Builder::setElementClass);
    context.deserialize(codedIn, builder, Builder::setSet);
    return builder;
  }

  private static final class Builder implements DeferredValue<Depset> {
    @Nullable private Class<?> elementClass;
    private NestedSet<?> set;

    @Override
    public Depset call() {
      Depset existing = deserializedBySet.get(set);
      if (existing != null && existing.getRawElementClass() == elementClass) {
        return existing;
      }
      Depset created = new Depset(elementClass, set);
      if (existing == null) {
        Depset raced = deserializedBySet.putIfAbsent(set, created);
        if (raced != null && raced.getRawElementClass() == elementClass) {
          return raced;
        }
      }
      return created;
    }

    private static void setElementClass(Builder builder, Object elementClass) {
      builder.elementClass = (Class<?>) elementClass;
    }

    private static void setSet(Builder builder, Object set) {
      builder.set = (NestedSet<?>) set;
    }
  }
}
