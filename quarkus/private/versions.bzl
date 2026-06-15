"Supported Quarkus versions and quarkifier release coordinates."

# Dict mapping minor version → supported patch version.
# The quarkifier is compiled against each minor version's APIs independently.
# Users MUST use one of these exact versions.
SUPPORTED_VERSIONS = {
    "3.27": "3.27.4",
    "3.33": "3.33.2",
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

# Coursier fat JAR, pinned for reproducible deployment artifact resolution.
COURSIER_VERSION = "2.1.24"
COURSIER_URL = "https://github.com/coursier/coursier/releases/download/v{v}/coursier.jar".format(v = COURSIER_VERSION)
COURSIER_SHA256 = "8c724dc204534353ea8263ba0af624979658f7ab62395f35b04f03ce5714f330"
