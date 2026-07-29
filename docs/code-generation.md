# Quarkus code generation

`rules_quarkus` runs Quarkus extension `CodeGenProvider` implementations in a
hermetic Bazel action before Java compilation. The action consumes the explicit
application model and declared inputs, then emits a deterministic source JAR.
Java is the initial supported generated language; generated Kotlin or Scala
sources fail with a direct diagnostic.

## Main sources

`quarkus_java_library` mirrors `java_library` and automatically reuses its
dependencies, resources, `testonly`, and `resource_strip_prefix` values for
generation:

```starlark
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_java_library")

quarkus_java_library(
    name = "messages",
    srcs = glob(["src/main/java/**/*.java"]),
    codegen_srcs = glob(["src/main/proto/**/*.proto"]),
    codegen_build_properties = {
        "quarkus.generate-code.grpc.scan-for-proto": "none",
    },
    deps = [
        "@maven//:io_quarkus_quarkus_grpc",
        # Add the direct Java dependencies referenced by generated sources.
        "@maven//:com_google_protobuf_protobuf_java",
        "@maven//:io_grpc_grpc_api",
        "@maven//:io_grpc_grpc_stub",
    ],
)
```

The default main source parent is `src/main`. A provider chooses its input
subdirectory beneath that parent, such as `src/main/proto` for gRPC.

## Test sources

Use test mode for inputs below `src/test`:

```starlark
quarkus_java_library(
    name = "test_lib",
    testonly = True,
    srcs = glob(["src/test/java/**/*.java"]),
    codegen_srcs = glob(["src/test/proto/**/*.proto"]),
    codegen_mode = "test",
    deps = [
        ":main_lib",
        "@maven//:io_quarkus_quarkus_junit",
    ],
)
```

Test model assembly must find exactly one local main application library.
Missing or ambiguous main applications fail during model assembly.

## Low-level rule

Use `quarkus_codegen` when composing generation with a custom Java rule:

```starlark
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_codegen")

quarkus_codegen(
    name = "generated",
    srcs = glob(["schema/**/*.proto"]),
    source_roots = ["schema"],
    resources = glob(["src/main/resources/**/*"]),
    resource_strip_prefix = "src/main/resources",
    deps = ["@maven//:io_quarkus_quarkus_grpc"],
)

java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]) + [":generated"],
    resources = glob(["src/main/resources/**/*"]),
    deps = ["@maven//:io_quarkus_quarkus_grpc"],
)
```

Low-level users must also put the declared resources in the final application
library. The rule exposes these diagnostic output groups:

- `quarkus_codegen_model`: the explicit application model;
- `quarkus_codegen_sources`: the unpackaged generated-source tree;
- `quarkus_codegen_aux`: non-Java files emitted beside generated sources.

For example:

```bash
bazel build //path:generated --output_groups=quarkus_codegen_model
bazel build //path:generated --output_groups=quarkus_codegen_aux
```

Source-root entries are package-relative, non-empty paths without `..`.
Every `srcs` input must be beneath one of them. Resources follow normal
`java_library` layout rules; set `resource_strip_prefix` for nonstandard
layouts.

## Dev regeneration

Initial dev startup compiles Bazel-generated sources. The dev target watches
the original generator inputs, rebuilds itself hermetically, and syncs the
newly compiled classes through the normal hot-reload mechanism:

```starlark
quarkus_app(
    name = "app",
    deps = [":messages"],
)
```

Production, test, and dev code generation therefore share the same declared
inputs, lifecycle-specific application model, sandbox, and action cache.

## Caching and outputs

The action key includes codegen inputs, resources, runtime and deployment
artifacts, platform properties, the serialized application model, lifecycle
mode, and sorted build properties. Generated source JAR entries use stable
ordering and timestamps, so identical inputs produce identical bytes and work
with Bazel's local or remote action cache.

Non-Java auxiliary files remain available through the output group above.
Kotlin and Scala generation are not compiled in this initial implementation.
