#!/usr/bin/env bash

set -o errexit -o nounset -o pipefail

# Argument provided by reusable workflow caller, see
# https://github.com/bazel-contrib/.github/blob/d197a6427c5435ac22e56e33340dff912bc9334e/.github/workflows/release_ruleset.yaml#L72
TAG=$1
# The prefix is chosen to match what GitHub generates for source archives
# This guarantees that users can easily switch from a released artifact to a source archive
# with minimal differences in their code (e.g. strip_prefix remains the same)
PREFIX="rules_quarkus-${TAG:1}"
ARCHIVE="rules_quarkus-$TAG.tar.gz"

# Resolve the commit SHA for the tag
COMMIT=$(git rev-list -n 1 ${TAG})

# NB: configuration for 'git archive' is in /.gitattributes
git archive --format=tar --prefix=${PREFIX}/ ${TAG} | gzip > $ARCHIVE
INTEGRITY=$(openssl dgst -sha256 -binary "$ARCHIVE" | openssl base64 -A)

# Add generated API docs to the release, see https://github.com/bazelbuild/bazel-central-registry/issues/5593
docs="$(mktemp -d)"; targets="$(mktemp)"
bazel --output_base="$docs" query --output=label --output_file="$targets" 'kind("starlark_doc_extract rule", //...)'
bazel --output_base="$docs" build --target_pattern_file="$targets"
tar --create --auto-compress \
    --directory "$(bazel --output_base="$docs" info bazel-bin)" \
    --file "$GITHUB_WORKSPACE/${ARCHIVE%.tar.gz}.docs.tar.gz" .

cat << EOF
## Using Bzlmod with Bazel 6 or greater

1. (Bazel 6 only) Enable with \`common --enable_bzlmod\` in \`.bazelrc\`.
2. Add to your \`MODULE.bazel\` file:

\`\`\`starlark
bazel_dep(name = "com_clementguillot_rules_quarkus", version = "${TAG:1}")
\`\`\`

### Git override

\`\`\`starlark
bazel_dep(name = "com_clementguillot_rules_quarkus")

git_override(
    module_name = "com_clementguillot_rules_quarkus",
    remote = "https://github.com/clementguillot/rules_quarkus.git",
    commit = "${COMMIT}",
)
\`\`\`

### Archive override

\`\`\`starlark
bazel_dep(name = "com_clementguillot_rules_quarkus")

archive_override(
    module_name = "com_clementguillot_rules_quarkus",
    urls = ["https://github.com/clementguillot/rules_quarkus/releases/download/${TAG}/rules_quarkus-${TAG}.tar.gz"],
    strip_prefix = "${PREFIX}",
    integrity = "sha256-${INTEGRITY}",
)
\`\`\`

EOF
