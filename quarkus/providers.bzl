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
        "input_files": "Exact workspace-relative files consumed by the generator.",
        "mode": "Declared mode: main or test.",
        "source_roots": "Workspace-relative CodeGenProvider source-parent paths.",
    },
)

QuarkusContinuousTestInfo = provider(
    doc = "Test model and compiled outputs consumed by a quarkus_app dev target.",
    fields = {
        "application_dependency_ids": "Direct model dependency ids from which the TEST application is selected.",
        "application_model": "TEST-mode quarkus-bazel-model-v1 JSON File.",
        "build_files": "Depset of BUILD files whose changes require restarting dev mode.",
        "classes_output_dirs": "Ordered list of compiled test class jars/directories in runtime classpath order.",
        "codegen_input_files": "Depset of exact workspace-relative test code-generation inputs.",
        "input_files": "Depset of exact declared test-graph source and resource files.",
        "model_classpath": "Depset of files referenced by the TEST-mode application model.",
        "build_properties": "Declared test JVM system properties.",
        "jvm_flags": "Declared flags for the shared dev/test child JVM.",
        "test_classes": "Explicit class selectors.",
        "test_packages": "Explicit package selectors.",
    },
)
