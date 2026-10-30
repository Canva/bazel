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

import com.google.common.base.Supplier;
import com.google.common.hash.HashCode;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.InfoItem;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCachingOptions;

/**
 * {@code bazel info} keys for the Canva wrapper.
 *
 * <p>The binary carries the upstream version label unchanged, since the label reaches lockfile
 * digests, so these are how the wrapper tells that it is running this build and which install the
 * cache keys name.
 */
final class SkycacheInfoItems {

  private SkycacheInfoItems() {}

  /** Present, and true, whenever {@link SkycacheModule} is. */
  static final class Enabled extends InfoItem {
    Enabled() {
      super("skycache-enabled", "Whether this Bazel has the Skycache module.", /* hidden= */ true);
    }

    @Override
    public byte[] get(
        Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env) {
      return print("true");
    }
  }

  /** The install MD5 in every cache key this server writes or reads. */
  static final class InstallMd5 extends InfoItem {
    InstallMd5() {
      super(
          "skycache-install-md5",
          "The Bazel install MD5 that analysis cache keys name: the server's own, or"
              + " --experimental_analysis_cache_server_checksum_override.",
          /* hidden= */ true);
    }

    @Override
    public byte[] get(
        Supplier<BuildConfigurationValue> configurationSupplier, CommandEnvironment env) {
      RemoteAnalysisCachingOptions options =
          env.getOptions().getOptions(RemoteAnalysisCachingOptions.class);
      HashCode override = options == null ? null : options.getServerChecksumOverride();
      return print((override != null ? override : env.getDirectories().getInstallMD5()).toString());
    }
  }
}
