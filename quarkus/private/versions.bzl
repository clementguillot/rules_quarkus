"Supported Quarkus versions and quarkifier release coordinates."

# Dict mapping minor version → latest tested patch version.
# The quarkifier is compiled against each minor version's APIs independently.
# Users can specify any patch version within a supported minor.
SUPPORTED_VERSIONS = {
    "3.27": "3.27.3",
    "3.33": "3.33.1",
}

# Version of rules_quarkus itself — must match the version in MODULE.bazel.
# Updated automatically by the release process (release_prep.sh).
_RULES_VERSION = "$Format:%(describe:tags=true)$"

RULES_VERSION = "0.0.0" if _RULES_VERSION.startswith("$Format") else _RULES_VERSION.replace("v", "", 1)

# GitHub repository for downloading the quarkifier deploy jar from releases.
GITHUB_OWNER = "clementguillot"
GITHUB_REPO = "rules_quarkus"

# Maven Central URL for Coursier-based deployment artifact resolution.
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
