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
│       │   ├── ExtensionScanner.java
│       │   ├── DeploymentArtifactResolver.java
│       │   ├── MavenCoordinateParser.java
│       │   ├── VersionChecker.java
│       │   └── ...
│       └── test/java/com/clementguillot/quarkifier/
│           ├── QuarkifierConfigPropertyTest.java   # Property-based test
│           ├── TestDataGenerator.java              # Random data for PBTs
│           └── ...
├── examples/                 # Example workspace (helloworld app)
├── e2e/smoke/                # Smoke test as external workspace
└── dev/                      # Gazelle and dev tooling
```

## Building Locally

### Build the quarkifier deploy jar

```bash
bazel build //quarkifier:quarkifier_deploy.jar
```

### Build everything

```bash
bazel build //...
```

## Running Tests

### Quarkifier unit + property tests

```bash
bazel test //quarkifier:quarkifier_test
```

Tests use JUnit Platform Console Launcher with `--select-package=com.clementguillot.quarkifier`.

### Smoke test (e2e)

```bash
cd e2e/smoke
bazel build //...
```

## Testing with the Examples Workspace

The `examples/` directory is a separate Bazel workspace that uses `rules_quarkus` via `local_path_override`. It requires the deploy jar to be built first:

```bash
# 1. Build the deploy jar in the root workspace
bazel build //quarkifier:quarkifier_deploy.jar

# 2. Run the example
cd examples
bazel run //helloworld:helloworld    # Production mode
bazel run //helloworld:dev           # Dev mode
```

The examples `MODULE.bazel` uses `local_path_override` to point at the parent directory:

```python
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "0.0.0")
local_path_override(module_name = "com_clementguillot_rules_quarkus", path = "..")
```

And `quarkifier_source_dir` to resolve the local deploy jar:

```python
quarkus.toolchain(
    quarkus_version = "3.20.6",
    lock_file = "//:maven_install.json",
    quarkifier_source_dir = "@com_clementguillot_rules_quarkus//:MODULE.bazel",
)
```

## Classloader Isolation in Dev Mode

Dev mode uses a single `quarkifier_deploy.jar` for both production and dev mode. Classloader isolation is handled at runtime by `DevModeLauncher.createDevJar()`, which filters the manifest classpath to exclude runtime extension JARs (ArC, REST, etc.) and SmallRye Config JARs. Since dev mode spawns a separate child JVM process, the parent process having these JARs on its classpath is irrelevant. See [dev-mode.md](dev-mode.md) for the full explanation.

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
SUPPORTED_VERSIONS = ["3.20.6"]
RULES_VERSION = "0.1.0"
GITHUB_OWNER = "clementguillot"
GITHUB_REPO = "rules_quarkus"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
```

## Adding a New Quarkus Version

1. Add the version to `SUPPORTED_VERSIONS` in `versions.bzl`
2. Update the Quarkus dependency versions in `MODULE.bazel`
3. Run `bazel run @maven//:pin` to update the lock file
4. Build and test: `bazel build //... && bazel test //quarkifier:quarkifier_test`
5. Test with the examples workspace

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
