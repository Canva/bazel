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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.LookupTopLevelTargetsResult;
import com.google.devtools.build.lib.skyframe.serialization.analysis.RemoteAnalysisCacheClient;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason;
import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.TopLevelTargetsMatchStatus;
import com.google.devtools.build.lib.skyframe.serialization.proto.DataType;
import com.google.devtools.build.lib.util.Bucket;
import com.google.protobuf.CodedInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * A {@link RemoteAnalysisCacheClient} backed by the analysis cache service.
 *
 * <p>A lookup returns the value with the invalidation-data header a stored entry carries in front
 * of it removed, and the node key from that header as the invalidation fingerprint. {@code
 * SkyValueRetriever} removes that header itself when it reads a value store, but hands a
 * cache-client response straight to the deserializer, so this path owes it the stripped form.
 */
final class GrpcAnalysisCacheClient implements RemoteAnalysisCacheClient {

  private static final LookupResult MISS =
      new Lookup(new byte[0], null, MissReason.MISS_REASON_SKYVALUE_MISS.getNumber());

  private final ReadQueue gets;

  private final AtomicLong bytesSent = new AtomicLong();
  private final AtomicLong bytesReceived = new AtomicLong();
  private final AtomicLong requestsSent = new AtomicLong();

  @SuppressWarnings("ArrayRecordComponent") // Mirrors LookupResult, which exposes arrays.
  private record Lookup(byte[] value, @Nullable byte[] invalidationFingerprint, int missReason)
      implements LookupResult {}

  GrpcAnalysisCacheClient(ReadQueue gets) {
    this.gets = gets;
  }

  @Override
  public ListenableFuture<LookupResult> lookup(byte[] key) {
    requestsSent.incrementAndGet();
    bytesSent.addAndGet(key.length);
    return Futures.transform(
        gets.enqueue(key),
        value -> {
          if (value == null) {
            // A miss is an empty value, not a failure: the retriever maps empty to NO_CACHED_DATA
            // and treats a failed future as a serialization error.
            return MISS;
          }
          bytesReceived.addAndGet(value.length);
          return stripInvalidationData(value);
        },
        MoreExecutors.directExecutor());
  }

  /**
   * Splits the leading data-type marker and the invalidation key that follows it from the value.
   *
   * <p>Stripped here rather than by the service because it is a property of this read path only:
   * the value store fetches nested shared values that were written without a header, and a server
   * applying this rule to every response would eat their first bytes.
   *
   * <p>The framing is written by {@code SelectedEntrySerializer} and must be read exactly as {@code
   * SkyValueRetriever} reads it; the two disagreeing produces a value that deserializes as garbage
   * rather than an error.
   */
  private static LookupResult stripInvalidationData(byte[] value) {
    CodedInputStream codedIn = CodedInputStream.newInstance(value);
    byte[] invalidationFingerprint = null;
    try {
      int ordinal = codedIn.readInt32();
      DataType dataType = DataType.forNumber(ordinal);
      if (dataType == null) {
        throw unexpectedDataType(ordinal, value);
      }
      switch (dataType) {
        case DATA_TYPE_EMPTY:
          break;
        case DATA_TYPE_FILE:
        case DATA_TYPE_LISTING:
          {
            var unusedKey = codedIn.readString();
            break;
          }
        case DATA_TYPE_ANALYSIS_NODE:
        case DATA_TYPE_EXECUTION_NODE:
          invalidationFingerprint = PackedFingerprint.readFrom(codedIn).toBytes();
          break;
        default:
          throw unexpectedDataType(ordinal, value);
      }
    } catch (IOException e) {
      // Thrown through the transform and surfaced by the retriever as a SerializationException.
      throw new IllegalStateException("parsing the invalidation data key", e);
    }
    int headerBytes = codedIn.getTotalBytesRead();
    return new Lookup(
        Arrays.copyOfRange(value, headerBytes, value.length),
        invalidationFingerprint,
        MissReason.MISS_REASON_UNSPECIFIED.getNumber());
  }

  private static IllegalStateException unexpectedDataType(int ordinal, byte[] value) {
    return new IllegalStateException(
        String.format(
            "unexpected data type with ordinal %d in value bytes=%s",
            ordinal, HexFormat.of().formatHex(value)));
  }

  @Override
  public Stats getStats() {
    long bytesSent = this.bytesSent.get();
    long bytesReceived = this.bytesReceived.get();
    long requestsSent = this.requestsSent.get();
    long batches = gets.batchesSent();
    // Latency is not tracked; the service's own metrics carry it.
    return new Stats() {
      @Override
      public long bytesSent() {
        return bytesSent;
      }

      @Override
      public long bytesReceived() {
        return bytesReceived;
      }

      @Override
      public long requestsSent() {
        return requestsSent;
      }

      @Override
      public long batches() {
        return batches;
      }

      @Override
      public List<Bucket> latencyMicros() {
        return List.of();
      }

      @Override
      public List<Bucket> batchLatencyMicros() {
        return List.of();
      }

      @Override
      public int matchStatus() {
        return TopLevelTargetsMatchStatus.MATCH_STATUS_UNSPECIFIED.getNumber();
      }
    };
  }

  /**
   * Always reports a match.
   *
   * <p>The metadata table is a service-side optimization for deciding whether a download is worth
   * starting at all. The service has none, and Bazel only asks when the supplier provides metadata
   * parameters, which {@link SkycacheServicesSupplier} does not.
   */
  @Override
  public LookupTopLevelTargetsResult lookupTopLevelTargets(
      long evaluatingVersion, String configurationHash, boolean useFakeStampData, String bazelVersion) {
    return new LookupTopLevelTargetsResult() {
      @Override
      public int status() {
        return TopLevelTargetsMatchStatus.MATCH_STATUS_MATCH.getNumber();
      }

      @Override
      public String statusMessage() {
        return "top-level target metadata is not tracked; proceeding";
      }
    };
  }

  /** Nothing to report: the service keeps no per-build state a bail-out would need to release. */
  @Override
  public void bailOutDueToMissingFingerprint() {}
}
