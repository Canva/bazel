load("@rules_java//java:defs.bzl", "java_binary", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
    visibility = ["//src:__subpackages__"],
)

java_test(
    name = "GitVersionsTest",
    srcs = ["GitVersionsTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/versioning:long_version_getter",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "WorkspaceInfoDiffAwarenessTest",
    srcs = ["WorkspaceInfoDiffAwarenessTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/cmdline",
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/skyframe:diff_awareness",
        "//src/main/java/com/google/devtools/build/lib/skyframe:workspace_info",
        "//src/main/java/com/google/devtools/build/lib/vfs",
        "//src/main/java/com/google/devtools/build/lib/vfs:pathfragment",
        "//src/main/java/com/google/devtools/build/lib/vfs/inmemoryfs",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party:jsr305",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_binary(
    name = "DevAnalysisCacheServer",
    testonly = True,
    srcs = [
        "DevAnalysisCacheServer.java",
        "DevSnapshotIndex.java",
    ],
    main_class = "com.google.devtools.build.lib.skycache.DevAnalysisCacheServer",
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/protobuf:analysis_cache_java_grpc",
        "//src/main/protobuf:analysis_cache_java_proto",
        "//src/main/protobuf:snapshot_index_java_grpc",
        "//src/main/protobuf:snapshot_index_java_proto",
        "//third_party:netty",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_test(
    name = "SnapshotIndexClientTest",
    srcs = [
        "DevSnapshotIndex.java",
        "SnapshotIndexClientTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:fingerprint_value_store",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis:lookup_result",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization/analysis/proto:analysis_cache_service_metadata_status_java_proto",
        "//src/main/java/com/google/devtools/build/lib/util:bucket",
        "//src/main/protobuf:snapshot_index_java_grpc",
        "//src/main/protobuf:snapshot_index_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
    ],
)

java_test(
    name = "LocalAnalysisStoreTest",
    srcs = ["LocalAnalysisStoreTest.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:fingerprint_value_store",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:key_bytes_provider",
        "//src/main/protobuf:snapshot_index_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:truth",
    ],
)

java_test(
    name = "StreamingFetchQueueTest",
    srcs = [
        "DevAnalysisCacheServer.java",
        "DevSnapshotIndex.java",
        "StreamingFetchQueueTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/protobuf:analysis_cache_java_grpc",
        "//src/main/protobuf:analysis_cache_java_proto",
        "//src/main/protobuf:snapshot_index_java_grpc",
        "//src/main/protobuf:snapshot_index_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:netty",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
    ],
)

java_test(
    name = "MirroredFingerprintValueStoreTest",
    srcs = [
        "DevAnalysisCacheServer.java",
        "DevSnapshotIndex.java",
        "MirroredFingerprintValueStoreTest.java",
    ],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/skycache",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization",
        "//src/main/java/com/google/devtools/build/lib/skyframe/serialization:fingerprint_value_store",
        "//src/main/protobuf:analysis_cache_java_grpc",
        "//src/main/protobuf:analysis_cache_java_proto",
        "//src/main/protobuf:snapshot_index_java_grpc",
        "//src/main/protobuf:snapshot_index_java_proto",
        "//third_party:guava",
        "//third_party:junit4",
        "//third_party:netty",
        "//third_party:truth",
        "//third_party/grpc-java:grpc-jar",
        "@com_google_protobuf//:protobuf_java",
    ],
)
