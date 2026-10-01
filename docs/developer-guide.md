# Developer Guide

This guide covers building, testing, and developing `rules_quarkus` itself.

## Project Structure

```
rules_quarkus/
├── MODULE.bazel              # Module definition + dev dependencies
├── quarkus/                  # Starlark rules and module extension
│   ├── extensions.bzl        # Bzlmod module extension (creates 3 repos)
│   ├── providers.bzl         # QuarkusAppInfo provider
│   ├── defs.bzl              # Re-exports for toolchains repo
│   └── private/
│       ├── quarkus_app_impl.bzl    # quarkus_app rule
│       ├── quarkus_dev_impl.bzl    # quarkus_dev rule
│       ├── classpath_utils.bzl     # Classpath collection utilities
│       ├── launcher.sh.tpl         # Production launcher template
│       ├── dev_launcher.sh.tpl     # Dev mode launcher template
│       └── versions.bzl            # Version constants
├── quarkifier/               # Java augmentation tool
│   ├── BUILD.bazel           # Build targets (lib, binary, tests)
│   └── src/
│       ├── main/java/com/clementguillot/quarkifier/
│       │   ├── QuarkifierLauncher.java
│       │   ├── QuarkifierConfig.java
│       │   ├── augmentation/AugmentationExecutor.java
│       │   ├── dev/DevModeLauncher.java
│       │   ├── extension/ExtensionScanner.java
│       │   ├── model/ExplicitApplicationModelBuilder.java
│       │   ├── model/transport/       # Strict v1 model and assembler
│       │   └── ...
│       ├── main/resources_3_33/  # Targeted Quarkus version for the 3.33 jar
│       ├── main/resources_3_40/  # Targeted Quarkus version for the 3.40 jar
│       └── test/java/com/clementguillot/quarkifier/
│           ├── QuarkifierConfigPropertyTest.java   # Property-based test
│           ├── TestDataGenerator.java              # Random data for PBTs
│           └── ...
├── examples/                 # Example workspaces
│   ├── helloworld_3_33/      # Quarkus 3.33 example (legacy LTS)
│   └── helloworld_3_40/      # Quarkus 3.40 example
├── e2e/smoke/                # E2E smoke tests (bzlmod, Bazel 7/8/9)
└── dev/                      # Gazelle and dev tooling
```

## Building Locally

### Build the quarkifier deploy jar

```bash
bazel build //quarkifier:quarkifier_3_33_deploy.jar
bazel build //quarkifier:quarkifier_3_40_deploy.jar
```

### Build everything

```bash
bazel build //...
```

## Running Tests

### Quarkifier unit + property tests

```bash
bazel test //quarkifier:quarkifier_test_3_33
bazel test //quarkifier:quarkifier_test_3_40
```

### Smoke test (e2e)

The `e2e/smoke` workspace validates the full `rules_quarkus` pipeline from an external consumer's perspective (bzlmod only):

```bash
cd e2e/smoke
bazel test //...
```

This exercises `quarkus_app`, `quarkus_test`, `quarkus_integration_test`, and
the module extension end-to-end.

## Testing with the Examples Workspace

The `examples/` directory contains separate Bazel workspaces per Quarkus version that use `rules_quarkus` via `local_path_override`. They require the deploy jar to be built first:

```bash
# 1. Build the deploy jar in the root workspace
bazel build //quarkifier:quarkifier_3_40_deploy.jar

# 2. Run the 3.40 example
cd examples/helloworld_3_40
bazel run //:helloworld    # Production mode
bazel run //:helloworld_dev   # Dev mode

# Or for the legacy 3.33 line:
bazel build //quarkifier:quarkifier_3_33_deploy.jar
cd examples/helloworld_3_33
bazel run //:helloworld    # Production mode
bazel run //:helloworld_dev   # Dev mode
```

Each example's `MODULE.bazel` uses `local_path_override` to point at the root directory:

```starlark
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.0.0")
local_path_override(module_name = "com_clementguillot_rules_quarkus", path = "../..")
```

And `quarkifier_source_dir` to resolve the local deploy jar:

```starlark
quarkus.toolchain(
    quarkus_version = "3.40.1",  # or "3.33.4"
    lock_file = "//:maven_install.json",
    quarkifier_source_dir = "@com_clementguillot_rules_quarkus//:MODULE.bazel",
)
```

## ApplicationModel parity with Maven

`dev/devui_dependency_parity.py` compares the Dev UI "Dependencies" graph of
the same application under `mvn quarkus:dev` and `bazel run :<app>_dev`. That
page is rendered from the post-curation `ApplicationModel` (one node per
resolved artifact, one link per `ResolvedDependency.getDependencies()` entry,
typed `runtime` or `deployment`), so the diff is a node-by-node, edge-by-edge
parity check of the Bazel-owned model. The application root is normalized to
`<app>` because Maven uses POM coordinates and Bazel its workspace identity.

```bash
export JAVA_HOME=...   # JDK 17+ for ./mvnw and the Coursier repository rule
python3 dev/devui_dependency_parity.py run \
    --workspace examples/helloworld_3_40 \
    --bazel-target //:helloworld_dev \
    --out-dir /tmp/parity
```

Multi-module Maven fixtures need their reactor installed first and a module
selector, e.g. `./mvnw install -DskipTests` in `examples/demo_extension` and
`--maven-args "-f app/pom.xml" --bazel-target //app:demo_extension_dev`.
The example `pom.xml` and `MODULE.bazel` must import the same platform BOM,
otherwise version differences are fixture noise. `diff` and `capture`
subcommands compare saved graphs or snapshot a running Dev UI; `--allowlist`
accepts reviewed differences keyed by the SHA-256 of their exact report line.

## Classloader Isolation in Dev Mode

Dev mode uses the same version-specific deploy jar as production mode, for example `quarkifier_3_33_deploy.jar` or `quarkifier_3_40_deploy.jar`. Classloader isolation is handled at runtime by `DevModeLauncher.createDevJar()`, which filters the manifest classpath to exclude runtime extension JARs (ArC, REST, etc.) and SmallRye Config JARs. Since dev mode spawns a separate child JVM process, the parent process having these JARs on its classpath is irrelevant. See [dev-mode.md](dev-mode.md) for the full explanation.

## Existing Tests

| Test Class | Type | What It Verifies |
|---|---|---|
| `ExtensionScannerTest` | Unit | Correct extension metadata extraction |
| `MavenCoordinateParserTest` | Unit | Maven coordinate extraction from artifact paths |
| `QuarkifierConfigTest` | Unit | CLI parsing error paths: missing args, unknown flags, invalid mode |
| `BazelApplicationModelAssemblerTest` | Unit | Exact graph, flags, conditionals, platforms, and workspace assembly |
| `BazelApplicationModelReaderTest` | Unit | Strict schema and semantic validation |
| `AugmentationModeTest` | Unit | Mode parsing, case insensitivity, invalid values |
| `DevModeLauncherTest` | Unit | `buildDevModeContext()` field correctness |

## Key Constants

Defined in `quarkus/private/versions.bzl`:

```starlark
# Dict mapping minor version → supported patch version
SUPPORTED_VERSIONS = {
    "3.33": "3.33.4",
    "3.40": "3.40.1",
}
_RULES_VERSION = "$Format:%(describe:tags=true)$"
RULES_VERSION = "0.0.0" if _RULES_VERSION.startswith("$Format") else _RULES_VERSION.replace("v", "", 1)
GITHUB_OWNER = "clementguillot"
GITHUB_REPO = "rules_quarkus"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
```

## Adding a New Quarkus Minor Version

1. Add the minor → patch mapping to `SUPPORTED_VERSIONS` in `quarkus/private/versions.bzl`
2. Add a `maven.install(name = "maven_X_Y", ...)` block in `MODULE.bazel` with Quarkus deps pinned to the new patch version and `lock_file = "//:maven_install_X_Y.json"`
3. Add `use_repo(maven, "maven_X_Y")` after the new `maven.install`
4. Add `quarkifier_lib_X_Y`, `quarkifier_X_Y`, `quarkifier_test_X_Y`, and `pmd_test_X_Y` targets in `quarkifier/BUILD.bazel` using `@maven_X_Y` deps
5. Create `src/main/resources_X_Y/quarkifier-version.properties` with the targeted `quarkus.version`. Java sources are shared and compiled against each minor; if a Quarkus API diverges, isolate it behind `ApplicationDatWriter`, `AppModelSerializerStrategy`, or `QuarkusModelVersionAdapter` before adding version-specific sources
6. Pin the lock file: `REPIN=1 bazel run @maven_X_Y//:pin --lockfile_mode=off`
7. Build and test: `bazel build //quarkifier:quarkifier_X_Y_deploy.jar && bazel test //quarkifier:quarkifier_test_X_Y`
8. Create an example workspace under `examples/helloworld_X_Y/`
9. Test with the example workspace: `cd examples/helloworld_X_Y && bazel run //:helloworld_dev`

## Using this as a development dependency of other rules

You'll commonly find that you develop in another WORKSPACE, such as
some other ruleset that depends on rules_quarkus, or in a nested
WORKSPACE in the integration_tests folder.

To always tell Bazel to use this directory rather than some release
artifact or a version fetched from the internet, run this from this
directory:

```sh
OVERRIDE="--override_repository=rules_quarkus=$(pwd)/rules_quarkus"
echo "common $OVERRIDE" >> ~/.bazelrc
```

This means that any usage of `@rules_quarkus` on your system will point to this folder.
