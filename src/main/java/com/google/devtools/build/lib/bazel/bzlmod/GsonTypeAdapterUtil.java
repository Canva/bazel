// Copyright 2023 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.DICT;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_BIMAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_LIST;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_MAP;
import static com.google.devtools.build.lib.bazel.bzlmod.DelegateTypeAdapterFactory.IMMUTABLE_SET;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.Table;
import com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.vfs.Path;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.ryanharter.auto.value.gson.GenerateTypeAdapter;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Base64;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.syntax.Location;

/**
 * Utility class to hold type adapters and helper methods to get gson registered with type adapters
 */
public final class GsonTypeAdapterUtil {

  public static final TypeAdapter<Version> VERSION_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, Version version) throws IOException {
          jsonWriter.value(version.toString());
        }

        @Override
        public Version read(JsonReader jsonReader) throws IOException {
          Version version;
          String versionString = jsonReader.nextString();
          try {
            version = Version.parse(versionString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse Version %s from the lockfile", versionString), e);
          }
          return version;
        }
      };

  public static final TypeAdapter<ModuleKey> MODULE_KEY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleKey moduleKey) throws IOException {
          jsonWriter.value(moduleKey.toString());
        }

        @Override
        public ModuleKey read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          try {
            return ModuleKey.fromString(jsonString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse ModuleKey %s version from the lockfile", jsonString),
                e);
          }
        }
      };

  public static final TypeAdapter<Label> LABEL_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, Label label) throws IOException {
          jsonWriter.value(label.getUnambiguousCanonicalForm());
        }

        @Override
        public Label read(JsonReader jsonReader) throws IOException {
          return Label.parseCanonicalUnchecked(jsonReader.nextString());
        }
      };

  public static final TypeAdapter<RepositoryName> REPOSITORY_NAME_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, RepositoryName repoName) throws IOException {
          jsonWriter.value(repoName.getName());
        }

        @Override
        public RepositoryName read(JsonReader jsonReader) throws IOException {
          return RepositoryName.createUnvalidated(jsonReader.nextString());
        }
      };

  public static final TypeAdapter<ModuleExtensionId> MODULE_EXTENSION_ID_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleExtensionId moduleExtId) throws IOException {
          String isolationKeyPart = moduleExtId.getIsolationKey().map(key -> "%" + key).orElse("");
          jsonWriter.value(
              moduleExtId.getBzlFileLabel()
                  + "%"
                  + moduleExtId.getExtensionName()
                  + isolationKeyPart);
        }

        @Override
        public ModuleExtensionId read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          var extIdParts = Splitter.on('%').splitToList(jsonString);
          Optional<ModuleExtensionId.IsolationKey> isolationKey;
          if (extIdParts.size() > 2) {
            try {
              isolationKey =
                  Optional.of(ModuleExtensionId.IsolationKey.fromString(extIdParts.get(2)));
            } catch (ParseException e) {
              throw new JsonParseException(
                  String.format(
                      "Unable to parse ModuleExtensionID isolation key: '%s' from the lockfile",
                      extIdParts.get(2)),
                  e);
            }
          } else {
            isolationKey = Optional.empty();
          }
          try {
            return ModuleExtensionId.create(
                Label.parseCanonical(extIdParts.get(0)), extIdParts.get(1), isolationKey);
          } catch (LabelSyntaxException e) {
            throw new JsonParseException(
                String.format(
                    "Unable to parse ModuleExtensionID bzl file label: '%s' from the lockfile",
                    extIdParts.get(0)),
                e);
          }
        }
      };

  public static final TypeAdapter<ModuleExtensionEvalFactors>
      MODULE_EXTENSION_FACTORS_TYPE_ADAPTER =
          new TypeAdapter<>() {

            private static final String OS_KEY = "os:";
            private static final String ARCH_KEY = "arch:";
            // This is used when the module extension doesn't depend on os or arch, to indicate that
            // its value is "general" and can be used with any platform
            private static final String GENERAL_EXTENSION = "general";

            @Override
            public void write(JsonWriter jsonWriter, ModuleExtensionEvalFactors extFactors)
                throws IOException {
              if (extFactors.isEmpty()) {
                jsonWriter.value(GENERAL_EXTENSION);
              } else {
                StringBuilder jsonBuilder = new StringBuilder();
                if (!extFactors.getOs().isEmpty()) {
                  jsonBuilder.append(OS_KEY).append(extFactors.getOs());
                }
                if (!extFactors.getArch().isEmpty()) {
                  if (jsonBuilder.length() > 0) {
                    jsonBuilder.append(",");
                  }
                  jsonBuilder.append(ARCH_KEY).append(extFactors.getArch());
                }
                jsonWriter.value(jsonBuilder.toString());
              }
            }

            @Override
            public ModuleExtensionEvalFactors read(JsonReader jsonReader) throws IOException {
              String jsonString = jsonReader.nextString();
              if (jsonString.equals(GENERAL_EXTENSION)) {
                return ModuleExtensionEvalFactors.create("", "");
              }

              String os = "";
              String arch = "";
              var extParts = Splitter.on(',').splitToList(jsonString);
              for (String part : extParts) {
                if (part.startsWith(OS_KEY)) {
                  os = part.substring(OS_KEY.length());
                } else if (part.startsWith(ARCH_KEY)) {
                  arch = part.substring(ARCH_KEY.length());
                }
              }
              return ModuleExtensionEvalFactors.create(os, arch);
            }
          };

  public static final TypeAdapter<ModuleExtensionId.IsolationKey> ISOLATION_KEY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, ModuleExtensionId.IsolationKey isolationKey)
            throws IOException {
          jsonWriter.value(isolationKey.toString());
        }

        @Override
        public ModuleExtensionId.IsolationKey read(JsonReader jsonReader) throws IOException {
          String jsonString = jsonReader.nextString();
          try {
            return ModuleExtensionId.IsolationKey.fromString(jsonString);
          } catch (ParseException e) {
            throw new JsonParseException(
                String.format("Unable to parse isolation key: '%s' from the lockfile", jsonString),
                e);
          }
        }
      };

  public static final TypeAdapter<byte[]> BYTE_ARRAY_TYPE_ADAPTER =
      new TypeAdapter<>() {
        @Override
        public void write(JsonWriter jsonWriter, byte[] value) throws IOException {
          jsonWriter.value(Base64.getEncoder().encodeToString(value));
        }

        @Override
        public byte[] read(JsonReader jsonReader) throws IOException {
          return Base64.getDecoder().decode(jsonReader.nextString());
        }
      };

  public static final TypeAdapterFactory OPTIONAL =
      new TypeAdapterFactory() {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          if (typeToken.getRawType() != Optional.class) {
            return null;
          }
          Type type = typeToken.getType();
          if (!(type instanceof ParameterizedType)) {
            return null;
          }
          Type elementType = ((ParameterizedType) typeToken.getType()).getActualTypeArguments()[0];
          var elementTypeAdapter = gson.getAdapter(TypeToken.get(elementType));
          if (elementTypeAdapter == null) {
            return null;
          }
          return (TypeAdapter<T>) new OptionalTypeAdapter<>(elementTypeAdapter);
        }
      };

  private static final class OptionalTypeAdapter<T> extends TypeAdapter<Optional<T>> {
    private final TypeAdapter<T> elementTypeAdapter;

    public OptionalTypeAdapter(TypeAdapter<T> elementTypeAdapter) {
      this.elementTypeAdapter = elementTypeAdapter;
    }

    @Override
    public void write(JsonWriter jsonWriter, Optional<T> t) throws IOException {
      Preconditions.checkNotNull(t);
      if (t.isEmpty()) {
        jsonWriter.nullValue();
      } else {
        elementTypeAdapter.write(jsonWriter, t.get());
      }
    }

    @Override
    public Optional<T> read(JsonReader jsonReader) throws IOException {
      if (jsonReader.peek() == JsonToken.NULL) {
        jsonReader.nextNull();
        return Optional.empty();
      } else {
        return Optional.of(elementTypeAdapter.read(jsonReader));
      }
    }
  }

  /**
   * Converts Guava tables into a JSON array of 3-tuples (one per cell). Each 3-tuple is a JSON
   * array itself (rowKey, columnKey, value). For example, a JSON snippet could be: {@code [
   * ["row1", "col1", "value1"], ["row2", "col2", "value2"], ... ]}
   */
  public static final TypeAdapterFactory IMMUTABLE_TABLE =
      new TypeAdapterFactory() {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
          if (typeToken.getRawType() != ImmutableTable.class) {
            return null;
          }
          Type type = typeToken.getType();
          if (!(type instanceof ParameterizedType)) {
            return null;
          }
          Type[] typeArgs = ((ParameterizedType) typeToken.getType()).getActualTypeArguments();
          if (typeArgs.length != 3) {
            return null;
          }
          var rowTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[0]));
          var colTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[1]));
          var valTypeAdapter = (TypeAdapter<Object>) gson.getAdapter(TypeToken.get(typeArgs[2]));
          if (rowTypeAdapter == null || colTypeAdapter == null || valTypeAdapter == null) {
            return null;
          }
          return (TypeAdapter<T>)
              new TypeAdapter<ImmutableTable<Object, Object, Object>>() {
                @Override
                public void write(JsonWriter jsonWriter, ImmutableTable<Object, Object, Object> t)
                    throws IOException {
                  jsonWriter.beginArray();
                  for (Table.Cell<Object, Object, Object> cell : t.cellSet()) {
                    jsonWriter.beginArray();
                    rowTypeAdapter.write(jsonWriter, cell.getRowKey());
                    colTypeAdapter.write(jsonWriter, cell.getColumnKey());
                    valTypeAdapter.write(jsonWriter, cell.getValue());
                    jsonWriter.endArray();
                  }
                  jsonWriter.endArray();
                }

                @Override
                public ImmutableTable<Object, Object, Object> read(JsonReader jsonReader)
                    throws IOException {
                  var builder = ImmutableTable.builder();
                  jsonReader.beginArray();
                  while (jsonReader.peek() != JsonToken.END_ARRAY) {
                    jsonReader.beginArray();
                    builder.put(
                        rowTypeAdapter.read(jsonReader),
                        colTypeAdapter.read(jsonReader),
                        valTypeAdapter.read(jsonReader));
                    jsonReader.endArray();
                  }
                  jsonReader.endArray();
                  return builder.buildOrThrow();
                }
              };
        }
      };

  /**
   * A variant of {@link Location} that converts the absolute path to the root module file to a
   * constant and back.
   */
  // protected only for @AutoValue
  @GenerateTypeAdapter
  @AutoValue
  protected abstract static class RootModuleFileEscapingLocation {
    // This marker string is neither a valid absolute path nor a valid URL and thus cannot conflict
    // with any real module file location.
    private static final String ROOT_MODULE_FILE_LABEL = "@@//:MODULE.bazel";

    public abstract String file();

    public abstract int line();

    public abstract int column();

    public Location toLocation(String moduleFilePath) {
      String file;
      if (file().equals(ROOT_MODULE_FILE_LABEL)) {
        file = moduleFilePath;
      } else {
        file = file();
      }
      return Location.fromFileLineColumn(file, line(), column());
    }

    public static RootModuleFileEscapingLocation fromLocation(
        Location location, String moduleFilePath) {
      String file;
      if (location.file().equals(moduleFilePath)) {
        file = ROOT_MODULE_FILE_LABEL;
      } else {
        file = location.file();
      }
      return new AutoValue_GsonTypeAdapterUtil_RootModuleFileEscapingLocation(
          file, location.line(), location.column());
    }
  }

  private static final class LocationTypeAdapterFactory implements TypeAdapterFactory {

    private final String moduleFilePath;

    public LocationTypeAdapterFactory(Path moduleFilePath) {
      this.moduleFilePath = moduleFilePath.getPathString();
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> typeToken) {
      if (typeToken.getRawType() != Location.class) {
        return null;
      }
      TypeAdapter<RootModuleFileEscapingLocation> relativizedLocationTypeAdapter =
          gson.getAdapter(RootModuleFileEscapingLocation.class);
      return (TypeAdapter<T>)
          new TypeAdapter<Location>() {

            @Override
            public void write(JsonWriter jsonWriter, Location location) throws IOException {
              relativizedLocationTypeAdapter.write(
                  jsonWriter,
                  RootModuleFileEscapingLocation.fromLocation(location, moduleFilePath));
            }

            @Override
            public Location read(JsonReader jsonReader) throws IOException {
              return relativizedLocationTypeAdapter.read(jsonReader).toLocation(moduleFilePath);
            }
          };
    }
  }

  public static Gson createLockFileGson(Path moduleFilePath) {
    return new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .enableComplexMapKeySerialization()
        .registerTypeAdapterFactory(GenerateTypeAdapter.FACTORY)
        .registerTypeAdapterFactory(DICT)
        .registerTypeAdapterFactory(IMMUTABLE_MAP)
        .registerTypeAdapterFactory(IMMUTABLE_LIST)
        .registerTypeAdapterFactory(IMMUTABLE_BIMAP)
        .registerTypeAdapterFactory(IMMUTABLE_SET)
        .registerTypeAdapterFactory(OPTIONAL)
        .registerTypeAdapterFactory(IMMUTABLE_TABLE)
        .registerTypeAdapterFactory(new LocationTypeAdapterFactory(moduleFilePath))
        .registerTypeAdapter(Label.class, LABEL_TYPE_ADAPTER)
        .registerTypeAdapter(RepositoryName.class, REPOSITORY_NAME_TYPE_ADAPTER)
        .registerTypeAdapter(Version.class, VERSION_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleKey.class, MODULE_KEY_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleExtensionId.class, MODULE_EXTENSION_ID_TYPE_ADAPTER)
        .registerTypeAdapter(
            ModuleExtensionEvalFactors.class, MODULE_EXTENSION_FACTORS_TYPE_ADAPTER)
        .registerTypeAdapter(ModuleExtensionId.IsolationKey.class, ISOLATION_KEY_TYPE_ADAPTER)
        .registerTypeAdapter(AttributeValues.class, new AttributeValuesAdapter())
        .registerTypeAdapter(byte[].class, BYTE_ARRAY_TYPE_ADAPTER)
        .create();
  }

  private GsonTypeAdapterUtil() {}
}
