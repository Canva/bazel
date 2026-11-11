// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ListenableFuture;
import javax.annotation.Nullable;

/**
 * A container class that holds an {@link #object} of type {@link T} and a possibly null {@link
 * ListenableFuture}. If the {@link ListenableFuture} returned by {@link #getFutureToBlockWritesOn}
 * is non-null, then, if {@link #object} is the serialized representation of some Bazel object, then
 * it should not be written anywhere until the {@link ListenableFuture} in {@link
 * #getFutureToBlockWritesOn} completes successfully.
 *
 * @param <T> Some serialized representation of an object, for instance a {@code byte[]} or a {@link
 *     com.google.protobuf.ByteString}.
 */
public abstract class SerializationResult<T> {
  private final T object;
  @Nullable private final byte[] references;

  private SerializationResult(T object, @Nullable byte[] references) {
    this.object = object;
    this.references = references;
  }

  /**
   * Returns references to the shared values {@link #getObject} refers to by fingerprint, as {@link
   * References} packs them, or null if there are none or they were not recorded.
   *
   * <p>A hint for {@link ReferencingWriter#put}, set only for results of {@link
   * ObjectCodecs#serializeMemoizedAsync}.
   */
  @Nullable
  public byte[] getReferences() {
    return references;
  }

  /** Returns a new {@link SerializationResult} that differs only in its {@link #getReferences}. */
  abstract SerializationResult<T> withReferences(@Nullable byte[] references);

  /**
   * Returns a new {@link SerializationResult} with the same future (if any) and {@code newObj}
   * replacing the current {@link #getObject}.
   */
  public abstract <S> SerializationResult<S> with(S newObj);

  /**
   * Returns a {@link ListenableFuture} that, if not null, must complete successfully before {@link
   * #getObject} can be written remotely.
   */
  @Nullable
  public abstract ListenableFuture<?> getFutureToBlockWritesOn();

  /** Returns the stored object that should not be written remotely before the future completes. */
  public T getObject() {
    return object;
  }

  public static <T> SerializationResult<T> create(
      T object, @Nullable ListenableFuture<?> futureToBlockWritesOn) {
    return futureToBlockWritesOn != null
        ? new ObjectWithFuture<>(object, futureToBlockWritesOn)
        : createWithoutFuture(object);
  }

  /** Creates a {@link SerializationResult} with a null future (no waiting necessary). */
  public static <T> SerializationResult<T> createWithoutFuture(T object) {
    return new ObjectWithoutFuture<>(object);
  }

  private static class ObjectWithoutFuture<T> extends SerializationResult<T> {
    private ObjectWithoutFuture(T obj) {
      this(obj, /* references= */ null);
    }

    private ObjectWithoutFuture(T obj, @Nullable byte[] references) {
      super(obj, references);
    }

    @Override
    public <S> SerializationResult<S> with(S newObj) {
      return new ObjectWithoutFuture<>(newObj, getReferences());
    }

    @Override
    SerializationResult<T> withReferences(@Nullable byte[] references) {
      return new ObjectWithoutFuture<>(getObject(), references);
    }

    @Override
    public ListenableFuture<?> getFutureToBlockWritesOn() {
      return null;
    }
  }

  private static class ObjectWithFuture<T> extends SerializationResult<T> {
    private final ListenableFuture<?> futureToBlockWritesOn;

    private ObjectWithFuture(T obj, ListenableFuture<?> futureToBlockWritesOn) {
      this(obj, futureToBlockWritesOn, /* references= */ null);
    }

    private ObjectWithFuture(
        T obj, ListenableFuture<?> futureToBlockWritesOn, @Nullable byte[] references) {
      super(obj, references);
      this.futureToBlockWritesOn = Preconditions.checkNotNull(futureToBlockWritesOn, obj);
    }

    @Override
    public <S> SerializationResult<S> with(S newObj) {
      return new ObjectWithFuture<>(newObj, futureToBlockWritesOn, getReferences());
    }

    @Override
    SerializationResult<T> withReferences(@Nullable byte[] references) {
      return new ObjectWithFuture<>(getObject(), futureToBlockWritesOn, references);
    }

    @Override
    public ListenableFuture<?> getFutureToBlockWritesOn() {
      return futureToBlockWritesOn;
    }
  }
}
