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
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.unsafe.UnsafeProvider.unsafe;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import net.starlark.java.eval.Module;
import net.starlark.java.eval.StarlarkFunction;

/**
 * Codec for {@link StarlarkFunction}: a module's global function by the module and its name.
 *
 * <p>Without one, a function was serialized field by field, resolved syntax tree included. Every
 * action whose {@code Args} has a {@code map_each}, and every provider holding a function,
 * carried its own copy of the function's code, and a reader kept one per value: a cached {@code
 * //...} held 255 million {@code Identifier}s before running out of heap. A global function is
 * now the {@link Module}, itself serialized by its {@code .bzl} key, and the global's name, and
 * reads back as the very function this server loaded.
 *
 * <p>Other functions, lambdas and nested {@code def}s, are serialized as before, field by field.
 */
public final class StarlarkFunctionCodec extends DeferredObjectCodec<StarlarkFunction> {
  private static final StarlarkFunctionCodec INSTANCE = new StarlarkFunctionCodec();

  /** An instance field, written for functions that are not globals. */
  private record InstanceField(long offset, boolean intArray) {}

  /** The instance fields, in a fixed order. */
  private static final ImmutableList<InstanceField> FIELDS =
      Arrays.stream(StarlarkFunction.class.getDeclaredFields())
          .filter(f -> !Modifier.isStatic(f.getModifiers()))
          .sorted(Comparator.comparing(Field::getName))
          .map(f -> new InstanceField(unsafe().objectFieldOffset(f), f.getType() == int[].class))
          .collect(ImmutableList.toImmutableList());

  public static StarlarkFunctionCodec starlarkFunctionCodec() {
    return INSTANCE;
  }

  @Override
  public Class<StarlarkFunction> getEncodedClass() {
    return StarlarkFunction.class;
  }

  @Override
  public boolean autoRegister() {
    // Needs ModuleCodec, which needs Skyframe.
    return false;
  }

  @Override
  public void serialize(
      SerializationContext context, StarlarkFunction obj, CodedOutputStream codedOut)
      throws IOException, SerializationException {
    boolean global = obj.isGlobal();
    codedOut.writeBoolNoTag(global);
    if (global) {
      context.serialize(obj.getModule(), codedOut);
      codedOut.writeStringNoTag(obj.getName());
      return;
    }
    for (InstanceField field : FIELDS) {
      Object value = unsafe().getObject(obj, field.offset());
      if (field.intArray()) {
        // The registry has no codec for primitive arrays; DynamicCodec writes them inline too.
        int[] ints = (int[]) value;
        codedOut.writeInt32NoTag(ints.length);
        for (int i : ints) {
          codedOut.writeInt32NoTag(i);
        }
      } else {
        context.serialize(value, codedOut);
      }
    }
  }

  @Override
  public DeferredValue<StarlarkFunction> deserializeDeferred(
      AsyncDeserializationContext context, CodedInputStream codedIn)
      throws IOException, SerializationException {
    if (codedIn.readBool()) {
      var global = new GlobalBuilder();
      context.deserialize(codedIn, global, GlobalBuilder::setModule);
      global.name = codedIn.readString();
      return global;
    }
    StarlarkFunction instance;
    try {
      instance = (StarlarkFunction) unsafe().allocateInstance(StarlarkFunction.class);
    } catch (InstantiationException e) {
      throw new SerializationException("could not allocate a StarlarkFunction", e);
    }
    for (InstanceField field : FIELDS) {
      if (field.intArray()) {
        int[] ints = new int[codedIn.readInt32()];
        for (int i = 0; i < ints.length; i++) {
          ints[i] = codedIn.readInt32();
        }
        unsafe().putObject(instance, field.offset(), ints);
      } else {
        context.deserialize(codedIn, instance, field.offset());
      }
    }
    return () -> instance;
  }

  private static final class GlobalBuilder implements DeferredValue<StarlarkFunction> {
    private Module module;
    private String name;

    private static void setModule(GlobalBuilder builder, Object value) {
      builder.module = (Module) value;
    }

    @Override
    public StarlarkFunction call() {
      Object global = checkNotNull(module, "module not set").getGlobal(name);
      checkState(
          global instanceof StarlarkFunction,
          "global %s of %s is %s, not a function",
          name,
          module,
          global);
      return (StarlarkFunction) global;
    }
  }

  private StarlarkFunctionCodec() {}
}
