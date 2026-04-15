"Providers for rules_quarkus."

QuarkusAppInfo = provider(
    doc = "Information about a built Quarkus application",
    fields = {
        "fast_jar_dir": "Directory containing the Fast_Jar output",
        "application_classpath": "Depset of runtime classpath jars",
        "source_dirs": "Depset of source directories (for dev mode)",
        "quarkus_version": "String: Quarkus version used",
    },
)
