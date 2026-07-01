# Building Quarkus Extensions

`rules_quarkus` can build, use, and publish a custom [Quarkus extension](https://quarkus.io/guides/writing-extensions) entirely from Bazel — no Maven/Gradle plugin required.

A Quarkus extension has two modules:

- **runtime** — code that runs in the application (CDI beans, recorders, `@ConfigRoot` build-time config).
- **deployment** — `@BuildStep` code that runs at augmentation time and wires the runtime in.

The runnable example lives in [`examples/demo_extension`](../examples/demo_extension).

## Directory layout

```text
greeting-extension/
├── runtime/
│   ├── BUILD.bazel
│   └── src/main/
│       ├── java/...                          # @ConfigRoot, @Recorder, @ApplicationScoped beans
│       └── resources/META-INF/quarkus-extension.yaml
└── deployment/
    ├── BUILD.bazel
    └── src/main/java/...                      # @BuildStep processors
```

## Maven dependencies

The two modules need the Quarkus extension annotation processor and the deployment artifacts they compile against. Add to your `MODULE.bazel`:

```starlark
maven.install(
    artifacts = [
        "io.quarkus:quarkus-arc:3.33.2",
        # Extension annotation processor (emits *.list metadata + accessors)
        "io.quarkus:quarkus-extension-processor:3.33.2",
        # Deployment-module compile deps
        "io.quarkus:quarkus-arc-deployment:3.33.2",
        "io.quarkus:quarkus-core-deployment:3.33.2",
    ],
    lock_file = "//:maven_install.json",
)
```

## runtime/BUILD.bazel

The runtime is a plain `java_library` compiled with the extension annotation processor (which generates `META-INF/quarkus-config-roots.list` and recorder accessors). `quarkus_extension_runtime` then merges the generated `quarkus-extension.properties` descriptor into the jar and exposes the deployment classpath.

```starlark
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_plugin.bzl", "java_plugin")
load("@rules_quarkus//quarkus:defs.bzl", "quarkus_extension_runtime")

java_plugin(
    name = "quarkus_processor",
    generates_api = False,
    processor_class = "io.quarkus.annotation.processor.ExtensionAnnotationProcessor",
    deps = ["@maven//:io_quarkus_quarkus_extension_processor"],
)

java_library(
    name = "runtime_lib",
    srcs = glob(["src/main/java/**/*.java"]),
    plugins = [":quarkus_processor"],
    resources = glob(["src/main/resources/**/*"]),  # incl. quarkus-extension.yaml
    deps = [
        "@maven//:io_quarkus_quarkus_arc",
        "@maven//:io_quarkus_quarkus_core",
        "@maven//:io_smallrye_config_smallrye_config_core",
        "@maven//:jakarta_enterprise_jakarta_enterprise_cdi_api",
    ],
)

quarkus_extension_runtime(
    name = "runtime",
    group_id = "com.example",
    artifact_id = "greeting-extension",
    version = "1.0.0-SNAPSHOT",
    runtime_target = ":runtime_lib",
    deployment_target = "//greeting-extension/deployment:deployment",
)
```

`quarkus_extension_runtime` parameters:

| Parameter | Description |
|---|---|
| `runtime_target` | The runtime `java_library` (classes + `quarkus-extension.yaml` resource). |
| `deployment_target` | The deployment `java_library`. |
| `group_id` / `artifact_id` / `version` | Coordinates for the generated `deployment-artifact` descriptor (`<group_id>:<artifact_id>-deployment:<version>`). |

The `:runtime` target is what application code depends on, and it carries the deployment classpath so `quarkus_app` wires augmentation automatically.

## deployment/BUILD.bazel

The deployment is a plain `java_library` compiled with the same processor (which generates `META-INF/quarkus-build-steps.list`). It depends on the specific `-deployment` artifacts it uses plus the runtime library.

```starlark
load("@rules_java//java:java_library.bzl", "java_library")
load("@rules_java//java:java_plugin.bzl", "java_plugin")

java_plugin(
    name = "quarkus_processor",
    generates_api = False,
    processor_class = "io.quarkus.annotation.processor.ExtensionAnnotationProcessor",
    deps = ["@maven//:io_quarkus_quarkus_extension_processor"],
)

java_library(
    name = "deployment",
    srcs = glob(["src/main/java/**/*.java"]),
    plugins = [":quarkus_processor"],
    deps = [
        "@maven//:io_quarkus_quarkus_arc_deployment",
        "@maven//:io_quarkus_quarkus_core_deployment",
        "@maven//:io_quarkus_quarkus_core",
        "//greeting-extension/runtime:runtime_lib",
    ],
)
```

## Using the extension locally

Depend on the extension's **runtime** target from your application library — the deployment side is discovered and added to augmentation automatically (no `deployment_deps` needed):

```starlark
java_library(
    name = "lib",
    srcs = glob(["src/main/java/**/*.java"]),
    deps = ["//greeting-extension/runtime", ...],
)

quarkus_app(name = "app", deps = [":lib"])
```

On startup the extension's feature appears in the banner:

```text
INFO [io.quarkus] (...) Installed features: [cdi, greeting, rest, ...]
```

This works in production (`bazel run //app:app`), dev mode (`bazel run //app:app_dev`, with the extension listed in the Dev UI), and `@QuarkusTest` (`quarkus_test`).

## Publishing the extension

`:runtime` (the merged jar with the descriptor) is `java_export`-compatible, so publishing uses standard [`rules_jvm_external` `java_export`](https://github.com/bazel-contrib/rules_jvm_external). Both the runtime **and** deployment artifacts must be published — Quarkus resolves the `-deployment` artifact from Maven when a downstream build uses the extension.

The dependency lists are shared with the libraries (via a Starlark `RUNTIME_DEPS` / `DEPLOYMENT_DEPS` variable) so the compiled deps and the published POM stay in sync.

**runtime/BUILD.bazel** — the merged jar (no Maven coordinates) is bundled; `RUNTIME_DEPS` (with coordinates) become POM `<dependencies>`:

```starlark
load("@rules_jvm_external//:defs.bzl", "java_export")

java_export(
    name = "runtime_publish",
    maven_coordinates = "com.example:greeting-extension:1.0.0-SNAPSHOT",
    tags = ["manual"],
    exports = [":runtime"] + RUNTIME_DEPS,
)
```

**deployment/BUILD.bazel** — the bundled jar comes from `:runtime`'s `deployment_jar` output group, which is the deployment jar enriched with Maven metadata; the POM declares the runtime extension artifact plus the `-deployment` deps:

```starlark
filegroup(
    name = "deployment_jar",
    srcs = ["//greeting-extension/runtime:runtime"],
    output_group = "deployment_jar",
    tags = ["manual"],
)

java_import(
    name = "deployment_publish_lib",
    jars = [":deployment_jar"],
    tags = ["manual"],
)

java_export(
    name = "deployment_publish",
    maven_coordinates = "com.example:greeting-extension-deployment:1.0.0-SNAPSHOT",
    tags = ["manual"],
    exports = [
        ":deployment_publish_lib",
        "//greeting-extension/runtime:runtime_publish",  # -> runtime artifact as a POM dep
    ] + DEPLOYMENT_DEPS,
)
```

Publish both:

```bash
bazel run //greeting-extension/runtime:runtime_publish.publish
bazel run //greeting-extension/deployment:deployment_publish.publish
```
