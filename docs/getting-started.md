# Getting Started

This guide walks you through building and running a Quarkus application with Bazel using `rules_quarkus`.

## Prerequisites

- **Bazel 7+, 8+, or 9+** with Bzlmod enabled (the default)
- **Java 17+** (JDK, not just JRE)
- **Docker** (optional, required for Dev Services)

> **Note**: Only bzlmod is supported. WORKSPACE mode is not supported.

## 1. Add rules_quarkus to MODULE.bazel

```python
module(name = "my_project")

# Java rules
bazel_dep(name = "rules_java", version = "9.6.1")
bazel_dep(name = "rules_jvm_external", version = "6.10")

# Quarkus rules
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.1.0")
```

## 2. Declare Maven Dependencies

Use `rules_jvm_external` to declare your Quarkus runtime dependencies. Only declare the **runtime** artifacts — deployment artifacts are resolved automatically.

```python
maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
maven.install(
    artifacts = [
        "io.quarkus:quarkus-rest:3.33.1",
        "io.quarkus:quarkus-arc:3.33.1",
    ],
    lock_file = "//:maven_install.json",
)
use_repo(maven, "maven")
```

Run `bazel run @maven//:pin` to generate the `maven_install.json` lock file.

> **Supported versions**: 3.27.x LTS and 3.33.x. Replace the version above with your preferred Quarkus version.
>
> **Known limitation**: a single Bazel workspace can configure only one `quarkus.toolchain()` today. You can choose 3.27.x or 3.33.x per workspace, but you cannot build different Quarkus minor versions side by side in the same workspace yet.

## 3. Configure the Quarkus Toolchain

```python
quarkus = use_extension(
    "@com_clementguillot_rules_quarkus//quarkus:extensions.bzl",
    "quarkus",
)
quarkus.toolchain(
    quarkus_version = "3.33.1",
    lock_file = "//:maven_install.json",
)
use_repo(quarkus, "quarkus_deployment", "rules_quarkus_quarkifier", "rules_quarkus_toolchains")
```

The `lock_file` is used to auto-discover which Quarkus extensions you're using and download their `-deployment` counterparts.

The configured `quarkus_version` applies to every `quarkus_app`, generated `<name>_dev` target, and `quarkus_test` target in the workspace. For projects that need to validate multiple Quarkus minors, use separate workspaces, separate example directories, or separate CI jobs with different `MODULE.bazel` configurations.

### Toolchain Options

| Attribute | Default | Description |
|---|---|---|
| `quarkus_version` | (required) | Quarkus version, e.g. `"3.33.1"` or `"3.27.3"` |
| `lock_file` | `None` | Path to `maven_install.json` for extension auto-discovery |
| `extension_group_prefixes` | `["io.quarkus", "io.quarkiverse."]` | Maven groupId prefixes identifying Quarkus extensions |
| `quarkifier_tool` | `None` | Override quarkifier tool with a pre-built jar label |
| `quarkifier_source_dir` | `None` | Label in the rules_quarkus source dir for local dev builds |

## 4. Create Your Application

### java_library for your source code

```python
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

```python
load("@rules_quarkus_toolchains//:defs.bzl", "quarkus_app")

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

## 5. Build and Run

```bash
# Build the application
bazel build //:helloworld

# Run the application
bazel run //:helloworld
```

The application starts on `http://localhost:8080`. Visit `http://localhost:8080/hello` to see the response.

## 6. Dev Mode (Hot-Reload + Dev UI)

The `quarkus_app` macro automatically creates a `<name>_dev` target for dev mode:

```bash
bazel run //:helloworld_dev
```

This launches Quarkus in dev mode with:
- **Dev UI** at `http://localhost:8080/q/dev-ui`
- **Hot-reload** watching your source directories for changes

Dev mode runs in a separate JVM process. Press `Ctrl+C` to stop.

To opt out of the dev target, pass `dev = False` to `quarkus_app`:

```python
quarkus_app(
    name = "helloworld",
    dev = False,
    deps = [":lib"],
)
```

See [Dev Mode & Dev UI Integration](dev-mode.md) for details on how this works under the hood.

## quarkus_app Attributes

| Attribute | Type | Default | Description |
|---|---|---|---|
| `deps` | `label_list` | (required) | `java_library` and Maven artifact targets |
| `version` | `string` | `""` | Application version for Quarkus startup banner |
| `jvm_flags` | `string_list` | `[]` | JVM flags for runtime execution |
| `main_class` | `string` | `""` | Override main class (default: Quarkus runner) |

The `quarkus_version`, `quarkifier_tool`, and `deployment_deps` attributes are injected automatically by the toolchains macro.

## Multi-Module Projects

`quarkus_app` accepts dependencies from `java_library` targets across multiple Bazel packages:

```python
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

```python
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
        "io.quarkus:quarkus-rest:3.33.1",
        "io.quarkus:quarkus-arc:3.33.1",
        # Test dependencies
        "io.quarkus:quarkus-junit:3.33.1",
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
    quarkus_version = "3.33.1",
)
use_repo(quarkus, "quarkus_deployment", "rules_quarkus_quarkifier", "rules_quarkus_toolchains")
```
