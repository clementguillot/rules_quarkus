# Quarkifier Tool Reference

The Quarkifier (`com.clementguillot.quarkifier`) is a standalone Java tool that invokes the Quarkus internal build API (`io.quarkus.deployment`) to perform build-time augmentation. It is the core engine behind `rules_quarkus`.

- **Main class**: `com.clementguillot.quarkifier.QuarkifierLauncher`
- **Built against**: Quarkus 3.27.4 LTS and 3.33.2

## CLI Interface

The top-level command dispatches to `augmentation` and `enrich-extension`:

```
java -jar quarkifier_<minor>_deploy.jar [--help] [--version] <command>
```

### Augmentation

```
java -jar quarkifier_<minor>_deploy.jar \
  augmentation \
  --application-classpath <jar:jar:...> \
  --deployment-classpath <jar:jar:...> \
  [--application-classpath-file <path>] \
  [--deployment-classpath-file <path>] \
  [--core-deployment-classpath <jar:jar:...>] \
  [--core-deployment-classpath-file <path>] \
  --output-dir <path> \
  [--resources <path,path,...>] \
  [--mode normal|test|dev|native] \
  [--expected-quarkus-version <version>] \
  [--app-name <name>] \
  [--app-version <version>] \
  [--main-class <class>] \
  [--native-builder-image <image>] \
  [--source-dirs <dir,dir,...>] \
  [--classes-dir <path>] \
  [--bazel-targets <label,label,...>] \
  [--classes-output-dirs <dir,dir,...>] \
  [--workspace-dir <path>] \
  [--bazel-build-timeout-seconds <seconds>] \
  [--bazel-command <path>] \
  [--bazel-build-args <flag,flag,...>] \
  [--local-app-jars <jar:jar:...>] \
  [--local-app-jars-file <path>] \
  [-h|--help] \
  [-V|--version]
```

#### Flags

| Flag | Required | Default | Description |
|---|---|---|---|
| `--application-classpath` | Yes* | — | Colon-separated list of runtime jars |
| `--application-classpath-file` | No | — | File containing the application classpath (alternative to `--application-classpath`) |
| `--deployment-classpath` | Yes* | — | Colon-separated list of runtime + deployment jars |
| `--deployment-classpath-file` | No | — | File containing the deployment classpath (alternative to `--deployment-classpath`) |
| `--core-deployment-classpath` | No | `[]` | Colon-separated list of core deployment jars (dev mode only) |
| `--core-deployment-classpath-file` | No | — | File containing the core deployment classpath |
| `--output-dir` | Yes | — | Directory where Fast_Jar output is written |
| `--resources` | No | `[]` | Comma-separated list of resource file paths |
| `--mode` | No | `normal` | Augmentation mode: `normal`, `test`, `dev`, or `native` |
| `--expected-quarkus-version` | No | `null` | Expected Quarkus version for mismatch warnings |
| `--app-name` | No | `null` | Application name for Quarkus startup banner |
| `--app-version` | No | `null` | Application version for Quarkus startup banner |
| `--main-class` | No | `null` | Fully-qualified custom main class annotated with `@QuarkusMain` |
| `--native-builder-image` | No | `null` | Native builder image for `platform.quarkus.native.builder-image` |
| `--source-dirs` | No | `[]` | Comma-separated source directories for dev mode hot-reload |
| `--classes-dir` | No | `null` | Mutable directory for .class files in dev mode |
| `--bazel-targets` | No | `[]` | Comma-separated Bazel targets to rebuild on source changes |
| `--classes-output-dirs` | No | `[]` | Comma-separated bazel-bin output directories containing .class files |
| `--workspace-dir` | No | `null` | Bazel workspace root directory for running bazel build |
| `--bazel-build-timeout-seconds` | No | `600` | Timeout in seconds for bazel build process |
| `--bazel-command` | No | `bazel` | Bazel binary to invoke for hot-reload builds |
| `--bazel-build-args` | No | `[]` | Comma-separated extra flags for the hot-reload bazel build |
| `--local-app-jars` | No | `[]` | Colon-separated local workspace jars to use as application roots |
| `--local-app-jars-file` | No | — | File containing local app jars (alternative to `--local-app-jars`) |
| `-h`, `--help` | — | — | Show help message and exit |
| `-V`, `--version` | — | — | Show version info and exit |

*Either the inline flag or the `-file` variant must be provided. The `-file` variants read the classpath from a file (one line, colon-separated paths) to avoid "Argument list too long" errors on Linux when the classpath is very long. When both inline and file are provided, the file variant takes precedence regardless of argument order.

### Extension enrichment

```
java -jar quarkifier_<minor>_deploy.jar \
  enrich-extension \
  <runtime.jar> \
  <output.yaml> \
  <quarkus-version> \
  <classpath-file> \
  <extension-name>
```

This command reads `META-INF/quarkus-extension.yaml` from the runtime jar, adds
the Quarkus core version and extension dependencies discovered from the compile
classpath, then writes the enriched YAML to the requested output path.

### Exit Codes

| Code | Meaning |
|---|---|
| 0 | Success (warnings may have been emitted to stderr) |
| 1 | Command execution failure |
| 2 | Invalid CLI arguments (usage message on stderr) |

## Package Structure

```
com.clementguillot.quarkifier
├── QuarkifierLauncher              Entry point (thin shell, delegates to picocli)
├── QuarkifierCommand               Top-level picocli command dispatcher
├── AugmentationCommand             Parses augmentation options and executes the pipeline
├── EnrichExtensionCommand          Parses extension-enrichment arguments
├── QuarkifierConfig                Immutable record for config + toArgs() serialization
├── QuarkifierVersionProvider       Picocli IVersionProvider: reads version from classpath resource
├── AugmentationMode                Enum: NORMAL, TEST, DEV, NATIVE
├── AugmentationException           Checked exception wrapping build errors
├── BuildProperties                 Default build system properties
│
├── extension/                      Extension discovery and validation
│   ├── ExtensionInfo               Record: groupId, artifactId, version, sourceJar
│   ├── ExtensionScanner            Scans jars for quarkus-extension.properties
│   ├── ExtensionYamlEnricher       Enriches quarkus-extension.yaml build metadata
│   ├── DeploymentArtifactResolver  Maps extensions to their -deployment jars
│   ├── MissingDeploymentArtifactException
│   └── VersionChecker              Compares extension versions against expected
│
├── maven/                          Maven coordinate parsing
│   └── MavenCoordinateParser       Extracts GAV from jar file paths
│
├── model/                          ApplicationModel construction
│   └── QuarkusAppModelBuilder      Builds ApplicationModel from classpath jars
│
├── augmentation/                   Augmentation execution and post-processing
│   ├── AugmentationExecutor        Orchestrates bootstrap + augmentation
│   ├── FastJarAssembler            Post-processes output into runnable Fast_Jar
│   └── ApplicationDatWriter        Version-safe reflection wrapper for SerializedApplication.write()
│
├── dev/                            Dev mode
│   ├── DevModeLauncher             Builds DevModeContext, launches subprocess
│   ├── AppModelSerializerStrategy  Interface for version-specific model serialization
│   └── AppModelSerializerImpl      (in java_3_27/ or java_3_33/) Version-specific implementation
│
└── watcher/                        File watching for hot-reload
    └── BazelFileWatcher            Watches source dirs, triggers bazel build on changes
```

## QuarkifierConfig Record

Augmentation arguments are parsed by picocli into `AugmentationCommand` fields,
then forwarded to an immutable `QuarkifierConfig` record:

```java
public record QuarkifierConfig(
    List<Path> applicationClasspath,
    List<Path> deploymentClasspath,
    List<Path> coreDeploymentClasspath,
    Path outputDir,
    List<Path> resources,
    AugmentationMode mode,
    String expectedQuarkusVersion,
    String appName,
    String appVersion,
    String mainClass,
    String nativeBuilderImage,
    List<Path> sourceDirs,
    Path classesDir,
    List<String> bazelTargets,
    List<Path> classesOutputDirs,
    Path workspaceDir,
    long bazelBuildTimeoutSeconds,
    String bazelCommand,
    List<String> bazelBuildArgs,
    List<Path> localAppJars
) { ... }
```

The record supports round-trip serialization via `toArgs()` → `parse()`, which is verified by property-based tests (200 iterations).

## Augmentation Pipeline

```mermaid
graph LR
    subgraph Inputs
        AC[Application Classpath]
        DC[Deployment Classpath]
        VER[Expected Quarkus Version]
    end

    subgraph Pipeline
        PARSE[1. QuarkifierConfig.parse]
        SCAN[2. ExtensionScanner.scan]
        RESOLVE[3. DeploymentArtifactResolver.resolveAll]
        VCHECK[4. VersionChecker.check]
        EXEC[5. AugmentationExecutor.execute]
    end

    subgraph Output
        FJ[Fast_Jar Directory]
    end

    AC --> PARSE
    DC --> PARSE
    VER --> PARSE
    PARSE --> SCAN
    SCAN --> RESOLVE
    RESOLVE --> VCHECK
    VCHECK --> EXEC
    EXEC --> FJ
```

### Step 1: CLI Parsing

`QuarkifierCommand` dispatches to `AugmentationCommand`, which parses and
validates augmentation arguments, resolves `--*-file` fallbacks, and builds an
immutable `QuarkifierConfig`. Picocli exits with code 2 on invalid input. The
convenience method `QuarkifierConfig.parse()` delegates to the augmentation
subcommand for programmatic use.

### Step 2: Extension Scanning

`ExtensionScanner.scan()` reads `META-INF/quarkus-extension.properties` from each jar on the application classpath. For each extension found, it extracts:

| Field | Source | Example |
|---|---|---|
| `groupId` | `deployment-artifact` GAV property | `io.quarkus` |
| `artifactId` | Derived by stripping `-deployment` suffix | `quarkus-resteasy-reactive` |
| `version` | `deployment-artifact` GAV property | `3.27.4` |
| `sourceJar` | The jar that contained the metadata | `/path/to/quarkus-rest-3.27.4.jar` |

### Step 3: Deployment Artifact Resolution

`DeploymentArtifactResolver.resolveAll()` matches each extension to its `-deployment` jar on the deployment classpath. The naming convention is `artifactId + "-deployment"`. Missing deployment artifacts produce warnings (not failures).

### Step 4: Version Checking

`VersionChecker.check()` compares each extension's version against `expectedQuarkusVersion`. Mismatches produce warnings to stderr. The build continues regardless.

### Step 5: Augmentation

`AugmentationExecutor.execute()` orchestrates the build. Its behavior depends on the mode:

- **NORMAL/TEST**: Delegates to `QuarkusAppModelBuilder` to build the `ApplicationModel`, runs `QuarkusBootstrap` augmentation in-process, then delegates to `FastJarAssembler` for post-processing
- **DEV**: Delegates to `DevModeLauncher` (see [Dev Mode](dev-mode.md))

## ApplicationModel Construction

`QuarkusAppModelBuilder.build()` builds the model that Quarkus needs, bypassing Maven/Gradle resolution entirely:

1. **Register extensions** — scans runtime jars for `quarkus-extension.properties`, calls `modelBuilder.handleExtensionProperties()`, and manually registers capabilities (`provides-capabilities`, `requires-capabilities`)
2. **Set app artifact** — uses `MavenCoordinateParser` to extract coordinates from the app jar path
3. **Add runtime dependencies** — adds all runtime jars, marking extension jars with `setRuntimeExtensionArtifact()`
4. **Add deployment dependencies** — adds deployment-only jars, deduplicating by both `ArtifactKey` and `artifactId`. Marks `-dev` jars and `io.quarkus` `-spi` jars as runtime classpath entries
5. **Mark parent-first artifacts** — marks bootstrap/infrastructure jars as parent-first for the augment classloader
6. **Fix runner-parent-first flags** — workaround for the GACT key mismatch bug (see [Dev Mode](dev-mode.md#the-gact-key-mismatch-bug))
7. **Set empty PlatformImports** — required for JSON serialization in Quarkus 3.31+ (`ApplicationModel.asMap()` NPEs without it)

## Post-Processing (FastJarAssembler)

After Quarkus augmentation produces raw output, `FastJarAssembler.assemble()` runs four steps to normalize it into a complete Fast_Jar:

### assembleLibDirectories

Classifies runtime jars into `lib/boot/` (parent-first for LogManager) vs `lib/main/`:
- Clears jars placed by Quarkus augmentation (which use raw classpath filenames with `processed_` prefixes)
- Copies jars with clean Maven-convention names (`groupId.artifactId-version.jar`)
- Deduplicates by `artifactId:version`
- Excludes `quarkus-ide-launcher` (IDE/dev-mode helper that shades Maven/Gradle resolver classes)

### assembleResourcesJar

Creates `app/resources.jar` from user resource files (e.g., `application.properties`). Each resource is added with just its filename as the entry name.

### regenerateApplicationDat

Regenerates `quarkus/quarkus-application.dat` with correct relative paths. Jars in `lib/boot/` are registered as parent-first. This serialized metadata is read by the `RunnerClassLoader` at startup.

### fixRunnerManifest

Rewrites the `quarkus-run.jar` manifest to include boot jars in the `Class-Path` attribute. This allows `java -jar quarkus-run.jar` to find the bootstrap jars.

## MavenCoordinateParser

Extracts `groupId`/`artifactId`/`version` from jar file paths. Handles multiple path formats:

| Format | Example |
|---|---|
| Standard Maven repo | `.../io/quarkus/quarkus-arc/3.27.4/quarkus-arc-3.27.4.jar` |
| Bazel `processed_` prefix | `.../processed_quarkus-arc-3.27.4.jar` |
| Coursier cache (short) | `jars/quarkus-arc-3.27.4.jar` |

Uses stop segments (`external`, `v1`, `https`, `maven`, etc.) to identify where groupId segments begin when walking backwards from the filename.

## Error Handling

| Condition | Behavior | Exit Code |
|---|---|---|
| Missing deployment artifact | Warning to stderr (build continues) | 0 |
| Quarkus build API exception | `AugmentationException` with stack trace | 1 |
| Invalid CLI arguments | Usage message + error to stderr | 2 |
| Empty application classpath | `AugmentationException` | 1 |
| Augmentation produces no result | `AugmentationException` | 1 |
| Version mismatch | Warning to stderr (build continues) | 0 |
