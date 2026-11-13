// Copyright 2014 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.google.devtools.build.lib.events.ExtendedEventHandler;
import com.google.devtools.build.lib.vfs.Path;
import java.util.function.Function;

/**
 * An action which must know when it is skipped due to an action cache hit.
 *
 * <p>Use should be rare, as the action graph is a functional model.
 */
// TODO(lberki): Maybe merge this with RichDataProducingAction?
public interface NotifyOnActionCacheHit extends Action {
  /** A custom interface similar to {@link ActionExecutionContext}, but specific to cache hits. */
  interface ActionCachedContext {
    /**
     * An event listener to report messages to. Errors that signal an action failure should use
     * ActionExecutionException.
     */
    ExtendedEventHandler getEventHandler();

    /** Returns the execution root. See {@link CommandEnvironment#getExecRoot}. */
    Path getExecRoot();

    /** Returns the {@link ArtifactPathResolver} for this action. */
    ArtifactPathResolver getPathResolver();

    /**
     * Looks up and returns an action context implementation of the given interface type.
     */
    <T extends ActionContext> T getContext(Class<? extends T> type);
  }

  /**
   * Called when action has "cache hit", and therefore need not be executed. Returns false if there
   * was a failure to record the action cache hit, and so the action must be executed.
   *
   * @param context the action context for a cache hit
   */
  boolean actionCacheHit(ActionCachedContext context);

  /**
   * Called when the result of this action was taken from the remote analysis cache, so that the
   * action neither ran nor was looked up in the action cache, and none of its outputs are on disk.
   * Returns false if that result cannot stand in for running the action, and so the action must be
   * executed.
   *
   * @param context the action context for a cache hit
   * @param outputMetadata the metadata the retrieved result has for an output of this action, or
   *     null if it has none
   */
  default boolean resultRetrieved(
      ActionCachedContext context, Function<Artifact, FileArtifactValue> outputMetadata) {
    return false;
  }
}
