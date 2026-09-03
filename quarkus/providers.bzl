"Providers for rules_quarkus."

QuarkusAppInfo = provider(
    doc = "Information about a built Quarkus application",
    fields = {
        "application_classpath": "Depset of runtime classpath jars",
        "fast_jar_dir": "Deprecated alias of output_dir, retained for compatibility",
        "output_dir": "Directory containing the packaged application",
        "package_type": "String: Quarkus JVM package type",
        "quarkus_version": "String: Quarkus version used",
        "runner_path": "String: executable JAR path relative to output_dir",
        "source_jars": "Depset of transitive source jars (for dev mode)",
    },
)

QuarkusNativeInfo = provider(
    doc = "Information about a Quarkus native image build",
    fields = {
        "application_classpath": "Depset of runtime classpath jars",
        "binary": "File: the compiled native binary",
        "native_sources_dir": "Directory containing native-sources output from quarkifier",
        "quarkus_version": "String: Quarkus version used",
    },
)

QuarkusExtensionInfo = provider(
    doc = "Information about a locally-built Quarkus extension",
    fields = {
        "artifact_id": "Maven artifactId of the runtime extension.",
        "deployment_jar": "Merged local deployment artifact File.",
        "deployment_classpath": "Depset of jars required on the Quarkus deployment classpath",
        "group_id": "Maven groupId of the local extension.",
        "version": "Maven version of the local extension.",
    },
)

QuarkusCodeGenInfo = provider(
    doc = "Lifecycle metadata for one Quarkus CodeGenProvider action.",
    fields = {
        "input_dirs": "Workspace-relative directories holding the declared generator inputs.",
        "mode": "Declared mode: main or test.",
        "source_roots": "Workspace-relative CodeGenProvider source-parent paths.",
    },
)

QuarkusContinuousTestInfo = provider(
    doc = "Test model and compiled outputs consumed by a quarkus_app dev target.",
    fields = {
        "application_model": "TEST-mode quarkus-bazel-model-v1 JSON File.",
        "classes_output_dirs": "Depset of compiled test class jars/directories.",
        "codegen_input_dirs": "Depset of workspace-relative test code-generation input directories.",
        "model_classpath": "Depset of files referenced by the TEST-mode application model.",
        "resource_dirs": "Workspace-relative test resource directory paths.",
        "source_dirs": "Workspace-relative test Java source directory paths.",
    },
)
