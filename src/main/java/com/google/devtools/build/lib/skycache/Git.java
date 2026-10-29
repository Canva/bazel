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
package com.google.devtools.build.lib.skycache;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import javax.annotation.Nullable;

/**
 * Runs {@code git} in one working tree.
 *
 * <p>The environment is fixed rather than inherited: a developer's {@code GIT_DIR}, {@code
 * GIT_INDEX_FILE} or pager settings would otherwise change what the versions describe, and the
 * client environment of a Bazel command is not the server's to pass on.
 */
final class Git {

  private static final ImmutableMap<String, String> ENVIRONMENT =
      ImmutableMap.of(
          "PATH", "/usr/local/bin:/usr/bin:/bin",
          // Read-only commands like status otherwise take index.lock to refresh the index, and
          // would race the developer's own git.
          "GIT_OPTIONAL_LOCKS", "0",
          "GIT_TERMINAL_PROMPT", "0",
          "LC_ALL", "C");

  private final String binary;
  private final java.nio.file.Path workTree;

  Git(String binary, java.nio.file.Path workTree) {
    this.binary = binary;
    this.workTree = workTree;
  }

  java.nio.file.Path workTree() {
    return workTree;
  }

  /** Runs {@code git args} and returns its standard output. */
  byte[] run(String... args) throws IOException, InterruptedException {
    return run(/* stdin= */ null, args);
  }

  /** Runs {@code git args} and returns its output with surrounding whitespace removed. */
  String line(String... args) throws IOException, InterruptedException {
    return new String(run(args), UTF_8).strip();
  }

  /** Runs {@code git args} with {@code stdin} as its input and returns its standard output. */
  byte[] run(@Nullable byte[] stdin, String... args) throws IOException, InterruptedException {
    var command =
        ImmutableList.<String>builder()
            .add(binary, "-C", workTree.toString(), "--no-optional-locks")
            .add(args)
            .build();
    var builder = new ProcessBuilder(command);
    builder.environment().clear();
    builder.environment().putAll(ENVIRONMENT);
    String home = System.getProperty("user.home");
    if (home != null) {
      // Needed for safe.directory and the like. Nothing else of the user's environment is.
      builder.environment().put("HOME", home);
    }
    Process process = builder.start();
    try {
      // Feed stdin and read stderr on the side, so no pipe can fill while this thread waits on
      // another: cat-file answers each request line before it has read the next.
      var stderr = new FutureTask<>(() -> readAll(process.getErrorStream()));
      Thread.ofVirtual().start(stderr);
      Thread.ofVirtual().start(() -> write(process.getOutputStream(), stdin));
      byte[] stdout = process.getInputStream().readAllBytes();
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException(
            String.format(
                "'git %s' in %s exited with %d: %s",
                String.join(" ", args),
                workTree,
                exitCode,
                new String(stderrBytes(stderr), UTF_8).strip()));
      }
      return stdout;
    } finally {
      process.destroy();
    }
  }

  private static byte[] stderrBytes(FutureTask<byte[]> stderr) throws InterruptedException {
    try {
      return stderr.get();
    } catch (ExecutionException e) {
      return new byte[0];
    }
  }

  private static void write(OutputStream stream, @Nullable byte[] bytes) {
    try (stream) {
      if (bytes != null) {
        stream.write(bytes);
      }
    } catch (IOException e) {
      // git exited early; its exit code says why.
    }
  }

  private static byte[] readAll(InputStream stream) {
    try {
      return stream.readAllBytes();
    } catch (IOException e) {
      return new byte[0];
    }
  }
}
