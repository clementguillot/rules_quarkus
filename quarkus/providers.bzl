"Providers for rules_quarkus."

QuarkusAppInfo = provider(
    doc = "Information about a built Quarkus application",
    fields = {
        "application_classpath": "Depset of runtime classpath jars",
        "fast_jar_dir": "Directory containing the Fast_Jar output",
        "quarkus_version": "String: Quarkus version used",
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
