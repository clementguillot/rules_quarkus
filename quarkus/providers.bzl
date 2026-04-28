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
