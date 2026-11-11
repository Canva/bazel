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

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.collect.nestedset.NestedArrayCodec.nestedArrayCodec;

import com.google.common.collect.MapMaker;
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext;
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

/**
 * A codec implementation that is asynchronous-compatible.
 *
 * <p>This is required if deserialization of the {@link NestedSet} elements may perform Skyframe
 * lookups using {@link AsyncDeserializationContext#getSkyValue}.
 */
public final class DeferredNestedSetCodec extends DeferredObjectCodec<NestedSet<?>> {

  @Override
  public boolean autoRegister() {
    return false;
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<NestedSet<?>> getEncodedClass() {
    return (Class<NestedSet<?>>) ((Class<?>) NestedSet.class);
  }

  @Override
  public void serialize(SerializationContext context, NestedSet<?> obj, CodedOutputStream codedOut)
      throws SerializationException, IOException {
    checkState(!obj.isEmpty(), "empty NestedSet should have been a serialization constant");
    codedOut.writeInt32NoTag(obj.getDepthAndOrder());
    if (obj.isSingleton()) {
      codedOut.writeBoolNoTag(true);
      context.serialize(obj.getChildren(), codedOut);
      return;
    }
    codedOut.writeBoolNoTag(false);
    context.putSharedValue(
        (Object[]) obj.getChildren(), /* distinguisher= */ null, nestedArrayCodec(), codedOut);
  }

  @Override
  public DeferredValue<NestedSet<?>> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws SerializationException, IOException {
    int depthAndOrder = codedIn.readInt32();
    Order order = Order.values()[depthAndOrder & 3];
    int depth = depthAndOrder >> 2;
    boolean singleton = codedIn.readBool();
    var builder = new DeserializationBuilder(order, depth, singleton);
    if (singleton) {
      context.deserialize(codedIn, builder, DeserializationBuilder::setChildren);
    } else {
      context.getSharedValue(
          codedIn,
          /* distinguisher= */ null,
          nestedArrayCodec(),
          builder,
          DeserializationBuilder::setChildren);
    }
    return builder;
  }

  /**
   * Sets already deserialized, by the children they were built over.
   *
   * <p>A {@code NestedSet} compares by identity, and so does a struct holding one. The writer may
   * have put one set into several values that are each serialized separately; a reader that built
   * a new set for each would hold copies that no longer compare equal, and a depset of structs
   * holding them would keep every copy where the writer kept one. Children arrays are shared
   * values, deduplicated by fingerprint, so the same children mean the same set here. Weak on both
   * sides: a set references its children, and nothing here may keep either alive.
   */
  private static final ConcurrentMap<Object, NestedSet<?>> deserializedByChildren =
      new MapMaker().weakKeys().weakValues().makeMap();

  /**
   * Singleton sets already deserialized, by their one element and their order.
   *
   * <p>A singleton's element is written inside the set rather than as a shared value, so each
   * copy of the set brings its own copy of the element and the identity of the element says
   * nothing. The sets are the same if the elements are equal, which is all a depset asks of its
   * elements anyway. A crate's {@code CrateInfo} whose {@code deps} is a depset of one struct is
   * the case that showed this: a leaf crate reached through eight dependents was reported as
   * eight different crates.
   *
   * <p>The element is held strongly by its key, until the set, which also holds it, is collected.
   */
  private static final ConcurrentMap<SingletonKey, NestedSet<?>> deserializedSingletons =
      new MapMaker().weakValues().makeMap();

  private record SingletonKey(Order order, Object element) {}

  private static class DeserializationBuilder implements DeferredValue<NestedSet<?>> {
    private final Order order;
    private final int approxDepth;
    private final boolean singleton;
    private Object children;

    private DeserializationBuilder(Order order, int approxDepth, boolean singleton) {
      this.order = order;
      this.approxDepth = approxDepth;
      this.singleton = singleton;
    }

    @Override
    public NestedSet<?> call() {
      if (singleton) {
        return canonicalSingleton();
      }
      NestedSet<?> existing = deserializedByChildren.get(children);
      if (existing != null && existing.getOrder() == order) {
        return existing;
      }
      NestedSet<?> created = NestedSet.forDeserialization(order, approxDepth, children);
      if (existing == null) {
        NestedSet<?> raced = deserializedByChildren.putIfAbsent(children, created);
        if (raced != null && raced.getOrder() == order) {
          return raced;
        }
      }
      return created;
    }

    private NestedSet<?> canonicalSingleton() {
      NestedSet<?> created = NestedSet.forDeserialization(order, approxDepth, children);
      SingletonKey key;
      NestedSet<?> existing;
      try {
        key = new SingletonKey(order, children);
        existing = deserializedSingletons.putIfAbsent(key, created);
      } catch (RuntimeException e) {
        // An element that cannot be hashed cannot be in a depset that is ever flattened either.
        return created;
      }
      return existing == null ? created : existing;
    }

    private static void setChildren(DeserializationBuilder builder, Object children) {
      builder.children = children;
    }
  }

  static {
    // A sanity check of for NestedSet.depthAndOrder properties, which this codec depends on.
    checkState(Order.values().length == 4);
  }
}
