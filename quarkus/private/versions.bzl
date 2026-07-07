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

# SHA-256 checksums of the per-minor quarkifier release jars, keyed by minor
# version (e.g. "3.33").
#
# Intentionally EMPTY in the git tree: the jar hashes only exist once the
# release workflow has built the jars. release_prep.sh builds them, computes
# the checksums, and patches this map into the copy of this file that ships
# inside the release source archive — so the released tarball deliberately
# differs from `git archive <tag>` on this one declaration (same idea as the
# export-subst RULES_VERSION above). The BCR integrity of the archive then
# transitively pins the jars.
#
# When the map is empty (git checkout, or a custom quarkifier_url without
# quarkus.toolchain(quarkifier_sha256 = ...)), the download is unverified and
# the module extension prints the hash to pin.
#
# The exact line `QUARKIFIER_SHA256 = {}` is the patch anchor for
# release_prep.sh — do not reformat it.
QUARKIFIER_SHA256 = {}

# Maven Central URL for Coursier-based deployment artifact resolution.
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

# Default builder image for container-based native compilation, pinned by
# manifest-list digest (covers amd64 + aarch64). A bare tag is a mutable
# pointer: the same Bazel action key could silently produce binaries with
# different GraalVM versions over time or across machines — poisoning shared
# remote caches. The digest makes the action key honest; bumping the
# toolchain is an explicit change here (Renovate understands tag@digest).
DEFAULT_NATIVE_BUILDER_IMAGE = "quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25@sha256:4dda6a3d677b57614849557d0d18aac7326c4f30175142b0f1bb91bdcfc5c29a"

# Coursier fat JAR, pinned for reproducible deployment artifact resolution.
COURSIER_VERSION = "2.1.24"
COURSIER_URL = "https://github.com/coursier/coursier/releases/download/v{v}/coursier.jar".format(v = COURSIER_VERSION)
COURSIER_SHA256 = "8c724dc204534353ea8263ba0af624979658f7ab62395f35b04f03ce5714f330"
