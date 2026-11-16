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
package com.google.devtools.build.skyframe;

/** An interface used to annotate if a SkyKey is part of the conceptual Execution phase. */
public interface ExecutionPhaseSkyKey extends SkyKey {
  /**
   * Whether this key, although of the execution phase, is to be evaluated by the threads that
   * analyze: because it is known to have nothing to execute or to wait for.
   *
   * <p>The threads that execute are as many as there are jobs, hundreds where actions run
   * remotely, and are meant to spend their time waiting. Handing them a million tasks of a few
   * microseconds each has them wake, look for the task and go back to sleep, at the cost of more
   * processor time than the tasks take.
   */
  default boolean evaluatedWithAnalysis() {
    return false;
  }
}
