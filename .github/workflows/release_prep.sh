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

# Stamp quarkifier version resources before any jars are built. The source
# archive is produced from `git archive` later, so its extracted copy must be
# stamped separately as well.
_stamp_quarkifier_versions() {
  local root=$1
  local version=${TAG:1}
  for props in "$root"/quarkifier/src/main/resources_*/quarkifier-version.properties; do
    sed -i.bak "s/^quarkifier\\.version=dev$/quarkifier.version=${version}/" "$props"
    rm -f "$props.bak"
    if ! grep -Fxq "quarkifier.version=${version}" "$props"; then
      echo "ERROR: failed to stamp quarkifier version in $props" >&2
      exit 1
    fi
  done
}

# Keep local dry-runs from leaving tracked resource files modified. Release CI
# uses a disposable checkout, but restoring here also protects developer runs.
WORKTREE_VERSION_FILES=(quarkifier/src/main/resources_*/quarkifier-version.properties)
VERSION_BACKUP_DIR=$(mktemp -d)
for props in "${WORKTREE_VERSION_FILES[@]}"; do
  resource_dir=$(basename "$(dirname "$props")")
  cp "$props" "$VERSION_BACKUP_DIR/$resource_dir.properties"
done
_restore_worktree_versions() {
  for props in "${WORKTREE_VERSION_FILES[@]}"; do
    resource_dir=$(basename "$(dirname "$props")")
    cp "$VERSION_BACKUP_DIR/$resource_dir.properties" "$props"
  done
  rm -rf "$VERSION_BACKUP_DIR"
}
trap _restore_worktree_versions EXIT

_stamp_quarkifier_versions "."

# ---- Quarkifier deploy jars -------------------------------------------------
# Build the per-minor jars, release them next to the source archive, and patch
# their SHA-256 into the archive's versions.bzl. Chain of trust: the BCR
# integrity pins the archive, the archive pins the jars — so the tool jar the
# rules execute is verified end-to-end without needing the hashes in git
# (they only exist once this script has built the jars).
for target in $(bazel query 'filter("quarkifier_[0-9]+_[0-9]+_deploy.jar$", //quarkifier:*)'); do
  bazel build "$target"
done
_restore_worktree_versions
trap - EXIT

# sha256sum on Linux (CI); shasum on macOS for local dry-runs.
_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

SHA_BLOCK="QUARKIFIER_SHA256 = {"
CHECKSUM_ROWS=""
for jar in bazel-bin/quarkifier/quarkifier_*_deploy.jar; do
  base=$(basename "$jar")
  # quarkifier_3_27_deploy.jar → 3.27
  minor=$(echo "$base" | sed -E 's/quarkifier_([0-9]+)_([0-9]+)_deploy\.jar/\1.\2/')
  released="quarkifier-${minor}-${TAG}.jar"
  cp "$jar" "$released"
  sha=$(_sha256 "$released")
  SHA_BLOCK+=$'\n'"    \"${minor}\": \"${sha}\","
  CHECKSUM_ROWS+="| \`${released}\` | \`${sha}\` |"$'\n'
done
SHA_BLOCK+=$'\n'"}"

if [ -z "$CHECKSUM_ROWS" ]; then
  echo "ERROR: no quarkifier deploy jars found; refusing to release an empty QUARKIFIER_SHA256 map" >&2
  exit 1
fi

# ---- Source archive with checksums patched in -------------------------------
# NB: configuration for 'git archive' is in /.gitattributes
workdir=$(mktemp -d)
git archive --format=tar --prefix="${PREFIX}/" "${TAG}" | tar -C "$workdir" -xf -

VERSIONS_BZL="$workdir/$PREFIX/quarkus/private/versions.bzl"
if ! grep -q '^QUARKIFIER_SHA256 = {}$' "$VERSIONS_BZL"; then
  echo "ERROR: patch anchor 'QUARKIFIER_SHA256 = {}' not found in $VERSIONS_BZL" >&2
  exit 1
fi
# The block is passed via the environment: ENVIRON values are byte-exact,
# whereas awk -v would reinterpret backslash escape sequences.
SHA_BLOCK="$SHA_BLOCK" awk \
  '$0 == "QUARKIFIER_SHA256 = {}" { print ENVIRON["SHA_BLOCK"]; next } { print }' \
  "$VERSIONS_BZL" > "$VERSIONS_BZL.tmp"
mv "$VERSIONS_BZL.tmp" "$VERSIONS_BZL"

_stamp_quarkifier_versions "$workdir/$PREFIX"

# Deterministic tarball: stable entry order/ownership, commit timestamp for
# mtimes (matching git archive's convention), no gzip timestamp.
tar --create --directory "$workdir" \
    --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime="@$(git log -1 --format=%ct "$TAG")" \
    "$PREFIX" | gzip -n > "$ARCHIVE"
rm -rf "$workdir"

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

EOF
