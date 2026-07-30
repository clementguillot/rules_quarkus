# Quarkus code generation

The runnable example lives in [`examples/demo_grpc`](../examples/demo_grpc): a
gRPC producer/consumer pair whose shared library runs both the gRPC and Avro
generators, compiles handwritten Java against both generated APIs, and exposes
all three to a downstream consumer.

`rules_quarkus` runs Quarkus extension `CodeGenProvider` implementations in a
hermetic Bazel action before Java compilation. The action consumes the explicit
application model and declared inputs, then emits a deterministic source JAR.
Java is the initial supported generated language; generated Kotlin or Scala
sources fail with a direct diagnostic.

## Main sources

`quarkus_java_library` mirrors `java_library` and automatically reuses its
dependencies (including `exports` and `runtime_deps`), resources, `testonly`,
and `resource_strip_prefix` values for generation:

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

`codegen_mode = "test"` implies `testonly = True` unless it is set explicitly.
Test model assembly must find exactly one local main application library.
Missing or ambiguous main applications fail during model assembly. Main
libraries may use custom layouts; they do not need a `src/main` directory.

## Dependency-only generation

Some providers can generate entirely from dependency resources. For example,
gRPC can scan `.proto` files packaged in another Java target. Set
`codegen = True` to create the hidden generation target without local
`codegen_srcs`:

```starlark
quarkus_java_library(
    name = "dependency_messages",
    codegen = True,
    codegen_build_properties = {
        "quarkus.generate-code.grpc.scan-for-proto": "bazel.workspace:proto_dependency",
    },
    deps = [
        ":proto_dependency",
        "@maven//:io_quarkus_quarkus_grpc",
    ],
)
```

The low-level `quarkus_codegen` rule accepts `srcs = []` directly. Generation
also succeeds when no provider runs or when providers emit only auxiliary
files; the result is a deterministic empty source JAR.

`quarkus_java_library` follows `java_library` here rather than failing quietly.
`java_library` rejects `deps` without `srcs` (*deps not allowed without srcs;
move to runtime_deps?*), so an explicitly empty `codegen_srcs` without
`codegen = True` is rejected the same way:

```
codegen_srcs is empty; use codegen = True for dependency-only generation?
```

This matters most with `glob`: rename the input directory and the glob goes
empty, which on Bazel 8+ is already a load error (`allow_empty` defaults to
`False`) but on Bazel 7 would otherwise downgrade the target to a plain
`java_library` and only fail later, while compiling the handwritten sources
that expected generated types. Omit `codegen_srcs` entirely for a library that
does no generation.

## Low-level rule

Use `quarkus_codegen` when composing generation with a custom Java rule:

```starlark
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_codegen")

quarkus_codegen(
    name = "generated",
    srcs = glob(["schema/proto/**/*.proto"]),
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
- `quarkus_codegen_aux`: non-Java generated files and stable work products;
- `quarkus_codegen_work`: stable, non-executable files written to the
  provider work directory.

For example:

```bash
bazel build //path:generated --output_groups=quarkus_codegen_model
bazel build //path:generated --output_groups=quarkus_codegen_aux
bazel build //path:generated --output_groups=quarkus_codegen_work
```

Source-root entries are package-relative, non-empty paths without `..`. Every
`srcs` input must be beneath one of them, and must be a checked-in source file:
source parents are resolved against the workspace, so a rule-generated input
would never be visible to the provider and is rejected with a direct
diagnostic. Resources follow normal `java_library` layout rules; set
`resource_strip_prefix` for nonstandard layouts. A source root is the
provider's source *parent*: gRPC looks in its `proto` child, so
`source_roots = ["schema"]` means `schema/proto/**/*.proto`.

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

Declared build properties, application resource configuration, platform
properties, and default `quarkus.application.name`/`version` values are made
available during provider initialization. Effective `quarkus.*` values are
also exposed as JVM system properties while providers run. Ambient user
environment and JVM properties are deliberately excluded from this merge.

Deployment resolution retains every native tool classifier selected by the
extension graph, so the provider chooses the executable matching the Bazel
execution host rather than the repository-fetch host.

Non-Java auxiliary files remain available through the output groups above.
Temporary executable wrappers and copied native tools in the provider work
directory are excluded because their paths are not reproducible; stable
descriptor sets and similar files are retained. Kotlin and Scala generation
are not compiled in this initial implementation.
