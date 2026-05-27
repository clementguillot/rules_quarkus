# Native Image Compilation

Build GraalVM native binaries from your Quarkus application using Bazel. Native executables start in milliseconds and use a fraction of the memory compared to JVM mode.

## Overview

The `native=True` flag on `quarkus_app` creates a `<name>_native` target that produces a native binary. Under the hood, this uses a two-action pipeline:

1. **Quarkifier NATIVE augmentation** — runs the Quarkus build API with `quarkus.native.sources-only=true` to produce a native-sources directory (runner jar, lib/, native-image.args)
2. **native-image compilation** — invokes GraalVM's `native-image` tool via the `rules_graalvm` toolchain using the args file from step 1

## Prerequisites

- **Bazel 7+, 8+, or 9+** with Bzlmod enabled
- **Java 17+**
- **`rules_graalvm`** with a GraalVM distribution registered (provides the `native-image` binary)
- **C compiler** — provided automatically by Bazel's CC toolchain (Xcode on macOS, gcc on Linux)

## Setup

### 1. Add rules_graalvm to MODULE.bazel

```starlark
bazel_dep(name = "rules_graalvm", version = "0.11.1")

gvm = use_extension("@rules_graalvm//:extensions.bzl", "graalvm")
gvm.graalvm(
    name = "graalvm",
    distribution = "ce",
    java_version = "21",
    version = "21.0.2",
)
use_repo(gvm, "graalvm")

register_toolchains("@graalvm//:jvm", "@graalvm//:sdk")
```

> **GraalVM version**: Use a version compatible with your Quarkus version.
> Quarkus 3.33 targets GraalVM 25. If `rules_graalvm` doesn't ship your
> required version, use `git_override` to point to a fork that does.

### 2. Add native=True to your quarkus_app

```starlark
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app")

java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    resources = glob(["src/main/resources/**/*"]),
    deps = [
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:io_quarkus_quarkus_rest",
        "@maven//:jakarta_ws_rs_jakarta_ws_rs_api",
    ],
)

quarkus_app(
    name = "my_app",
    native = True,
    version = "1.0.0",
    deps = [":lib"],
)
```

This creates three targets:
- `:my_app` — JVM Fast_Jar (production)
- `:my_app_dev` — Dev mode with hot-reload
- `:my_app_native` — Native binary

## Usage

```bash
# Build the native binary
bazel build //:my_app_native

# Run the native binary
bazel run //:my_app_native

# The JVM target still works as before
bazel run //:my_app
```

## How It Works

### Action 1: QuarkusNativeAugmentation

The quarkifier runs in NATIVE mode, which sets:
- `quarkus.native.enabled=true`
- `quarkus.native.sources-only=true`

This triggers Quarkus's native-sources-only build path, producing:

```text
<name>-native-sources/
└── native-sources/
    ├── <name>-runner.jar          # Thin runner jar with all augmented bytecode
    ├── lib/                        # All dependency jars
    ├── native-image.args           # CLI arguments for native-image
    └── graalvm.version             # Expected GraalVM version
```

### Action 2: NativeImage

Invokes `native-image` from the `rules_graalvm` toolchain with:
- The `native-image.args` file produced by Action 1
- `-H:CCompilerPath=<path>` pointing to Bazel's CC toolchain compiler
- `-o <output>` to write the binary to Bazel's declared output path

The CC toolchain is resolved hermetically — no `use_default_shell_env` or system PATH dependency.

## GraalVM Version Compatibility

| Quarkus Version | Required GraalVM | Notes |
|-----------------|-----------------|-------|
| 3.27.x LTS | GraalVM 21+ | Works with `rules_graalvm` 0.11.1 stock |
| 3.33.x LTS | GraalVM 25+ | Requires a fork of `rules_graalvm` with GraalVM 25 support |

### Using a fork for GraalVM 25

If your Quarkus version requires GraalVM 25 (not yet in `rules_graalvm` upstream), use `git_override`:

```starlark
bazel_dep(name = "rules_graalvm", version = "0.11.1")

git_override(
    module_name = "rules_graalvm",
    remote = "https://github.com/<your-fork>/rules_graalvm.git",
    commit = "<commit-sha-with-graalvm-25-support>",
)

gvm = use_extension("@rules_graalvm//:extensions.bzl", "graalvm")
gvm.graalvm(
    name = "graalvm",
    distribution = "ce",
    java_version = "25",
    version = "25.0.1",
)
use_repo(gvm, "graalvm")

register_toolchains("@graalvm//:jvm", "@graalvm//:sdk")
```

## Troubleshooting

### `native-image` not found / toolchain resolution error

Ensure you have `register_toolchains("@graalvm//:jvm", "@graalvm//:sdk")` in your `MODULE.bazel` and that the `gvm.graalvm()` extension is configured.

### Class initialization errors

These typically indicate a GraalVM version mismatch. Quarkus generates `native-image.args` targeting a specific GraalVM version. Check `graalvm.version` in the build output and ensure your registered GraalVM matches.

### `cc` not found

The rule uses Bazel's CC toolchain (`-H:CCompilerPath`). On macOS, ensure Xcode command line tools are installed (`xcode-select --install`). On Linux, ensure `gcc` or `clang` is available.

### Build takes a long time

Native image compilation is CPU and memory intensive (typically 1-3 minutes, using 2-8GB RAM). Bazel caches the result — subsequent builds with unchanged sources skip the native-image step entirely.

## Provider: QuarkusNativeInfo

The `<name>_native` target provides `QuarkusNativeInfo` with:

| Field | Type | Description |
|-------|------|-------------|
| `native_sources_dir` | Directory | The native-sources output from augmentation |
| `binary` | File | The compiled native binary |
| `application_classpath` | Depset | Runtime classpath jars |
| `quarkus_version` | String | Quarkus version used |
