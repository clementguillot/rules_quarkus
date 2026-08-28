# Getting Started

This guide walks you through building and running a Quarkus application with Bazel using `rules_quarkus`.

## Prerequisites

- **Bazel 7+, 8+, or 9+** with Bzlmod enabled (the default)
- **Java 17+** (JDK, not just JRE)
- **Docker** (optional, required for Dev Services)

> **Note**: Only bzlmod is supported. WORKSPACE mode is not supported.

## 1. Add rules_quarkus to MODULE.bazel

```starlark
module(name = "my_project")

# Java rules
bazel_dep(name = "rules_java", version = "9.6.1")
bazel_dep(name = "rules_jvm_external", version = "6.10")

# Quarkus rules
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.1.0")
```

## 2. Declare Maven Dependencies

Use `rules_jvm_external` to declare your Quarkus runtime dependencies. Only declare the **runtime** artifacts — deployment artifacts are resolved automatically.

```starlark
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "io.quarkus:quarkus-rest:3.33.2",
        "io.quarkus:quarkus-arc:3.33.2",
    ],
    lock_file = "//:maven_install.json",
)
use_repo(maven, "maven")
```

Run `bazel run @maven//:pin` to generate the `maven_install.json` lock file.

> **Supported versions**: You must use exactly `3.27.4` or `3.33.2`. These are the only supported patch versions.
>
> **Known limitation**: a single Bazel workspace can configure only one `quarkus.toolchain()` today. You can choose `3.27.4` or `3.33.2` per workspace, but you cannot build different Quarkus minor versions side by side in the same workspace yet.

## 3. Configure the Quarkus Toolchain

```starlark
quarkus = use_extension(
    "@com_clementguillot_rules_quarkus//quarkus:extensions.bzl",
    "quarkus",
)
quarkus.toolchain(
    quarkus_version = "3.33.2",
    lock_file = "//:maven_install.json",
)
use_repo(quarkus, "rules_quarkus")
```

The `lock_file` identifies the exact runtime jars to scan. For every jar carrying
`META-INF/quarkus-extension.properties`, rules_quarkus resolves the descriptor's
exact `deployment-artifact` coordinate; it does not infer names from groupIds or
an `-deployment` suffix.

The configured `quarkus_version` applies to every `quarkus_app`, generated
`<name>_dev` target, `quarkus_test`, and `quarkus_integration_test` target in
the workspace. For projects that need to validate both supported versions, use
separate workspaces, separate example directories, or separate CI jobs with
different `MODULE.bazel` configurations.

### Toolchain Options

| Attribute | Default | Description |
|---|---|---|
| `quarkus_version` | (required) | Quarkus version: `"3.27.4"` or `"3.33.2"` |
| `lock_file` | `None` | Path to `maven_install.json` for extension auto-discovery |
| `extension_group_prefixes` | `["io.quarkus", "io.quarkiverse."]` | Deprecated compatibility option; descriptor discovery no longer filters by groupId |
| `quarkifier_source_dir` | `None` | Label in the rules_quarkus source dir for local dev builds |
| `quarkifier_sha256` | `""` | SHA-256 pin for the quarkifier jar download. Released versions carry their own checksums, so this is only needed with `git_override`/`archive_override` (the build prints the hash to pin when verification is off) |

## 4. Create Your Application

### java_library for your source code

```starlark
# BUILD.bazel
load("@rules_java//java:java_library.bzl", "java_library")

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
```

### quarkus_app for the runnable application

```starlark
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_app")

quarkus_app(
    name = "helloworld",
    version = "1.0.0",
    deps = [":lib"],
)
```

### Example REST endpoint

```java
// src/main/java/com/example/GreetingResource.java
package com.example;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class GreetingResource {

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello from Quarkus + Bazel!";
    }
}
```

### application.properties

```properties
# src/main/resources/application.properties
quarkus.http.port=8080
```

### Declared build-time configuration

Use `build_properties` for values that must be visible while Quarkus performs
augmentation. The map is serialized deterministically in sorted order and
declared to Bazel, so changing any key or value invalidates the relevant
actions without reading the ambient environment. The packaged JVM, native, and
dev lifecycles carry it as a UTF-8 `.properties` action input; `quarkus_test`
carries the same map as JVM system properties on its launcher, because Quarkus
augments inside the test JVM.

```starlark
quarkus_app(
    name = "helloworld",
    build_properties = {
        "quarkus.profile": "prod",
        "quarkus.package.jar.compress": "true",
        # Non-Quarkus keys can satisfy ${...} expressions in Quarkus config.
        "deployment.region": "eu-west",
    },
    version = "1.0.0",
    deps = [":lib"],
)
```

Declared names must be non-empty and must not contain `=`. The equals sign is
representable in the `.properties` file but not in the `-Dname=value` JVM flags
that carry the same map into the test and dev JVMs, so it is rejected during
Bazel analysis rather than meaning two different things per lifecycle. Spaces,
colons, and other characters are preserved by both channels. Values are
unrestricted.

The `quarkus_app` map is shared automatically with its generated
`<name>_dev` and `<name>_native` targets. Direct `quarkus_codegen` targets also
expose `build_properties`. When using `quarkus_java_library` code generation,
use `codegen_build_properties` because that action belongs to the library
rather than to a downstream application.

The packaged JVM and native lifecycles scope the map around augmentation only,
so the values are gone by the time the packaged application runs. The
`<name>_dev` target instead passes them as JVM system properties to the dev
child process, where they stay visible to run-time configuration for the whole
dev session — a declared `quarkus.profile`, for example, suppresses `%dev.*`
entries in `application.properties`. See [Dev Mode](dev-mode.md).

`quarkus_test` also accepts `build_properties`, but Quarkus performs its test
augmentation inside the test JVM. The values are therefore passed as JVM
system properties and remain visible for the duration of the test; use this
attribute only for test-augmentation configuration. Launcher-owned properties
such as the serialized application-model path, dynamic integration-test ports,
coverage settings, and package layout take precedence over `build_properties`.
Explicit `jvm_flags` retain their existing last-wins behavior and can override
launcher defaults when required.

`quarkus_integration_test` deliberately has no `build_properties` attribute:
it launches an application that has already been augmented and packaged.
Declare build-time configuration on the referenced `quarkus_app`; use the
integration test's `jvm_flags` only for test-runner configuration.

Build properties are not a replacement for run-time configuration. Values
that may change after packaging should remain in `application.properties`, an
external config file, environment variables, or run-time JVM flags. Dedicated
rule attributes take precedence over conflicting map entries: for example,
`package_type` controls `quarkus.package.jar.type`, and native rules always
force native sources-only augmentation.

Do not place secrets in `build_properties`. The generated file is an ordinary
Bazel output and may be retained in `bazel-bin`, the local disk cache, or a
remote cache. Supply credentials and other secrets at run time instead.

## 5. Build and Run

```bash
# Build the application
bazel build //:helloworld

# Run the application
bazel run //:helloworld
```

The application starts on `http://localhost:8080`. Visit `http://localhost:8080/hello` to see the response.

### Inspect the resolved application model

Every application exposes diagnostic output groups without changing the normal
build outputs:

```bash
bazel build //:helloworld \
  --output_groups=quarkus_model
```

- `bazel-bin/helloworld.quarkus-bazel-model-v1.json` is the strict Bazel-owned
  input: exact graph edges, scopes, classpath facts, workspace modules,
  and platforms.

Model assembly fails before Quarkus starts when it finds missing coordinates,
dangling edges, duplicate identities, ambiguous artifact joins, or a missing
descriptor-declared deployment artifact. This fail-closed behavior is internal;
application BUILD declarations do not gain model attributes.

## 6. Test and collect coverage

Run a `quarkus_test` normally or under Bazel coverage without changing its
BUILD declaration:

```bash
bazel test //:test
bazel coverage //:test --combined_report=lcov
```

Bazel prints the per-test LCOV path when coverage finishes. The
`--combined_report=lcov` flag also emits the combined report at
`bazel-out/_coverage/_coverage_report.dat`. `--instrumentation_filter` selects
Bazel target labels whose compiled classes are instrumented; it does not filter
source paths directly. For example, instrument only the `//app:lib` application
library:

```bash
bazel coverage //:test --combined_report=lcov --instrumentation_filter='//app:lib$'
```

A package-wide filter such as `//app/...` also includes test-library targets
declared under that package hierarchy.

The `io.quarkus:quarkus-jacoco` extension is optional for Bazel coverage. When
it is declared:

- ordinary `bazel test` keeps Quarkus JaCoCo enabled and stores its execution
  data plus HTML, XML, and CSV reports in the test's undeclared outputs;
- `bazel coverage` disables Quarkus instrumentation for that execution so
  Bazel alone instruments the classes and produces LCOV.

This routing is automatic; `quarkus_test` gains no coverage attributes.

### Packaged integration tests

Use `quarkus_integration_test` for classes annotated with
`@QuarkusIntegrationTest`. The rule launches the selected packaged application
in a separate process while JUnit runs in the Bazel test process:

```starlark
load(
    "@rules_quarkus//quarkus:defs.bzl",
    "quarkus_integration_test",
)

quarkus_integration_test(
    name = "integration_test",
    app = ":helloworld",
    deps = [":test_lib"],
)
```

The test dependency closure must include the application library directly or
transitively. By default, package and automatic discovery select classes ending
in `IT`; `test_classes` can explicitly select a differently named annotated
class. Integration HTTP and HTTPS ports are dynamically allocated so Bazel can
run targets concurrently. Override them with `jvm_flags` when a fixed port is
required:

```starlark
jvm_flags = ["-Dquarkus.http.test-port=8081"]
```

Run the packaged test with:

```bash
bazel test //:integration_test
```

When included in a `bazel coverage` command, integration tests still run but
the separately launched application is not instrumented. Their execution does
not contribute LCOV records; use `quarkus_test` for application coverage.

## 7. Dev Mode (Hot-Reload + Dev UI)

The `quarkus_app` macro automatically creates a `<name>_dev` target for dev mode:

```bash
bazel run //:helloworld_dev
```

This launches Quarkus in dev mode with:
- **Dev UI** at `http://localhost:8080/q/dev-ui`
- **Hot-reload** watching your source directories for changes

Dev mode runs in a separate JVM process. Press `Ctrl+C` to stop.

To opt out of the dev target, pass `dev = False` to `quarkus_app`:

```starlark
quarkus_app(
    name = "helloworld",
    dev = False,
    deps = [":lib"],
)
```

See [Dev Mode & Dev UI Integration](dev-mode.md) for details on how this works under the hood.

### JVM package types

`fast-jar` remains the default. Select another Quarkus JVM layout with
`package_type`; the resulting target stays directly runnable with `bazel run`
and can be passed to `quarkus_integration_test`.

```starlark
quarkus_app(
    name = "helloworld_uber",
    dev = False,
    package_type = "uber-jar",
    deps = [":lib"],
)
```

| `package_type` | Quarkus | Runner inside the Bazel tree artifact | Purpose |
|---|---|---|---|
| `fast-jar` | 3.27, 3.33 | `quarkus-app/quarkus-run.jar` | Recommended indexed production layout |
| `uber-jar` | 3.27, 3.33 | `quarkus-run.jar` | Single executable JAR |
| `mutable-jar` | 3.27, 3.33 | `quarkus-app/quarkus-run.jar` | Re-augmentable layout for remote development |
| `legacy-jar` | 3.27, 3.33 | `quarkus-run.jar` | Deprecated pre-1.12 thin-JAR layout |
| `aot-jar` | 3.33 only | `quarkus-app/quarkus-run.jar` | System-classloader layout used for AOT-cache workflows |

`aot-jar` selects the AOT-compatible package layout. It does not train or
embed an `app.aot` cache; cache generation remains a separate, JDK-specific
workflow. Selecting `aot-jar` with Quarkus 3.27 fails during Bazel analysis.

## quarkus_app Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `deps` | `label_list` | (required) | `java_library` and Maven artifact targets |
| `version` | `string` | `""` | Application version for Quarkus startup banner |
| `build_properties` | `string_dict` | `{}` | Declared build-time configuration shared with the `_dev` and `_native` targets; see [Declared build-time configuration](#declared-build-time-configuration) |
| `jvm_flags` | `string_list` | `[]` | JVM flags for runtime execution |
| `main_class` | `string` | `""` | Override main class (default: Quarkus runner) |
| `package_type` | `string` | `"fast-jar"` | JVM package layout; see the table above |
| `dev` | `bool` | `True` | Also create the `<name>_dev` target |
| `dev_build_args` | `string_list` | `[]` | Extra Bazel flags reused by hot-reload builds |
| `native` | `bool` | `False` | Also create `<name>_native` using `rules_graalvm` |
| `native_container_build` | `bool` | `False` | Also create `<name>_native` using Docker or Podman |

The `quarkus_version`, `quarkifier_tool`, and `deployment_deps` attributes are injected automatically by the `@rules_quarkus//quarkus:defs.bzl` macros.

## Multi-Module Projects

`quarkus_app` accepts dependencies from `java_library` targets across multiple Bazel packages:

```starlark
# //shared/BUILD.bazel
java_library(
    name = "shared",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = ["@maven//:jakarta_ws_rs_jakarta_ws_rs_api"],
    visibility = ["//visibility:public"],
)

# //app/BUILD.bazel
quarkus_app(
    name = "app",
    deps = [
        ":lib",
        "//shared",
    ],
)
```

All transitive dependencies are collected via `JavaInfo` providers and included in the augmentation classpath.

## Complete MODULE.bazel Example

This is the full `MODULE.bazel` from the `examples/helloworld_3_33` workspace:

```starlark
module(name = "helloworld_3_33")

bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.0.0", dev_dependency = True)
local_path_override(
    module_name = "com_clementguillot_rules_quarkus",
    path = "../..",
)

# JVM rules
bazel_dep(name = "rules_java", version = "9.6.1")
bazel_dep(name = "rules_jvm_external", version = "6.10")

maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "io.quarkus:quarkus-rest:3.33.2",
        "io.quarkus:quarkus-arc:3.33.2",
        # Test dependencies
        "io.quarkus:quarkus-junit:3.33.2",
        "io.quarkus:quarkus-jacoco:3.33.2",
        "io.rest-assured:rest-assured:5.5.6",
        "org.junit.jupiter:junit-jupiter:5.13.4",
        "org.junit.platform:junit-platform-console-standalone:1.13.4",
        "org.junit.platform:junit-platform-launcher:1.13.4",
    ],
    lock_file = "//:maven_install.json",
)
use_repo(maven, "maven")

quarkus = use_extension("@com_clementguillot_rules_quarkus//quarkus:extensions.bzl", "quarkus")
quarkus.toolchain(
    lock_file = "//:maven_install.json",
    quarkus_version = "3.33.2",
)
use_repo(quarkus, "rules_quarkus")
```
