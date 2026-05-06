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
│       │   ├── AugmentationExecutor.java
│       │   ├── DevModeLauncher.java
│       │   ├── AppModelSerializerStrategy.java
│       │   ├── ExtensionScanner.java
│       │   ├── DeploymentArtifactResolver.java
│       │   ├── MavenCoordinateParser.java
│       │   ├── VersionChecker.java
│       │   └── ...
│       ├── main/java_3_27/   # Version-specific: AppModelSerializerImpl (JOS format)
│       ├── main/java_3_33/   # Version-specific: AppModelSerializerImpl (JSON format)
│       └── test/java/com/clementguillot/quarkifier/
│           ├── QuarkifierConfigPropertyTest.java   # Property-based test
│           ├── TestDataGenerator.java              # Random data for PBTs
│           └── ...
├── examples/                 # Example workspaces
│   ├── helloworld_3_27/      # Quarkus 3.27 example
│   └── helloworld_3_33/      # Quarkus 3.33 example
├── e2e/smoke/                # E2E smoke tests (bzlmod, Bazel 7/8/9)
└── dev/                      # Gazelle and dev tooling
```

## Building Locally

### Build the quarkifier deploy jar

```bash
bazel build //quarkifier:quarkifier_3_27_deploy.jar
bazel build //quarkifier:quarkifier_3_33_deploy.jar
```

### Build everything

```bash
bazel build //...
```

## Running Tests

### Quarkifier unit + property tests

```bash
bazel test //quarkifier:quarkifier_test_3_27
bazel test //quarkifier:quarkifier_test_3_33
```

### Smoke test (e2e)

The `e2e/smoke` workspace validates the full `rules_quarkus` pipeline from an external consumer's perspective (bzlmod only):

```bash
cd e2e/smoke
bazel test //...
```

This exercises `quarkus_app`, `quarkus_test`, and the module extension end-to-end.

## Testing with the Examples Workspace

The `examples/` directory contains separate Bazel workspaces per Quarkus version that use `rules_quarkus` via `local_path_override`. They require the deploy jar to be built first:

```bash
# 1. Build the deploy jar in the root workspace
bazel build //quarkifier:quarkifier_3_27_deploy.jar

# 2. Run the 3.27 example
cd examples/helloworld_3_27
bazel run //:helloworld    # Production mode
bazel run //:helloworld_dev   # Dev mode

# Or for 3.33:
bazel build //quarkifier:quarkifier_3_33_deploy.jar
cd examples/helloworld_3_33
bazel run //:helloworld    # Production mode
bazel run //:helloworld_dev   # Dev mode
```

Each example's `MODULE.bazel` uses `local_path_override` to point at the root directory:

```python
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.0.0")
local_path_override(module_name = "com_clementguillot_rules_quarkus", path = "../..")
```

And `quarkifier_source_dir` to resolve the local deploy jar:

```python
quarkus.toolchain(
    quarkus_version = "3.27.3",  # or "3.33.1"
    lock_file = "//:maven_install.json",
    quarkifier_source_dir = "@com_clementguillot_rules_quarkus//:MODULE.bazel",
)
```

## Classloader Isolation in Dev Mode

Dev mode uses the same version-specific deploy jar as production mode, for example `quarkifier_3_27_deploy.jar` or `quarkifier_3_33_deploy.jar`. Classloader isolation is handled at runtime by `DevModeLauncher.createDevJar()`, which filters the manifest classpath to exclude runtime extension JARs (ArC, REST, etc.) and SmallRye Config JARs. Since dev mode spawns a separate child JVM process, the parent process having these JARs on its classpath is irrelevant. See [dev-mode.md](dev-mode.md) for the full explanation.

## Property-Based Testing

The project uses property-based testing (PBT) to verify universal correctness properties across randomly generated inputs.

### TestDataGenerator

`TestDataGenerator` produces randomized inputs for property tests:
- Uses a **seeded `Random(42)`** for reproducibility
- Generates **200 iterations** per property (`TRIES = 200`)
- Produces random `QuarkifierConfig` instances with valid structure

```java
static Stream<QuarkifierConfig> randomValidConfigs() {
    return IntStream.range(0, TRIES).mapToObj(i -> randomConfig());
}
```

### Writing a Property Test

Property tests use JUnit Jupiter parameterized tests with `@MethodSource`:

```java
@ParameterizedTest
@MethodSource("com.clementguillot.quarkifier.TestDataGenerator#randomValidConfigs")
void roundTrip(QuarkifierConfig original) throws Exception {
    // Feature: rules-quarkus, Property 4: CLI argument parsing round-trip
    String[] args = original.toArgs();
    QuarkifierConfig parsed = QuarkifierConfig.parse(args);
    assertEquals(original, parsed);
}
```

Each property test is tagged with a comment: `Feature: rules-quarkus, Property {number}: {property_text}`

### Existing Tests

| Test Class | Type | What It Verifies |
|---|---|---|
| `QuarkifierConfigPropertyTest` | PBT | `toArgs()` → `parse()` round-trip (200 iterations) |
| `DeploymentArtifactResolverTest` | PBT | `artifactId + "-deployment"` naming convention |
| `ExtensionScannerTest` | PBT | Correct GAV extraction from extension properties |
| `MavenCoordinateParserTest` | PBT | Same artifactId+version from different path formats |
| `VersionCheckerTest` | PBT | Reports exactly the mismatched extensions |
| `QuarkifierConfigTest` | Unit | CLI parsing error paths: missing args, unknown flags, invalid mode |
| `AugmentationModeTest` | Unit | Mode parsing, case insensitivity, invalid values |
| `MissingDeploymentArtifactExceptionTest` | Unit | Exception message contains both artifact IDs |
| `DevModeLauncherTest` | Unit | `buildDevModeContext()` field correctness |

## Key Constants

Defined in `quarkus/private/versions.bzl`:

```python
# Dict mapping minor version → latest tested patch version
SUPPORTED_VERSIONS = {
    "3.27": "3.27.3",
    "3.33": "3.33.1",
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
5. Create `src/main/java_X_Y/` with version-specific source files (at minimum `AppModelSerializerImpl.java`)
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
