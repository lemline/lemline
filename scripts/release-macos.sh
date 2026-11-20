#!/usr/bin/env bash
# SPDX-License-Identifier: BUSL-1.1
#
# Builds the macOS arm64 native binary of a release tag on this Mac and attaches it to the
# GitHub release, with the same archive layout and checksum format as the Linux build of
# .github/workflows/release.yaml.
#
# GitHub's free macOS runners (7 GB) cannot hold the native build (about 6.5 GB), so the
# release workflow builds Linux, Windows and the jar, and this script adds macOS by hand.
# See docs/release-procedure.md.
#
# Usage:
#   scripts/release-macos.sh v0.7.0              # build, check, upload to the v0.7.0 release
#   scripts/release-macos.sh v0.7.0 --no-upload  # build and check only (e.g. before tagging)
set -euo pipefail

TAG="${1:-}"
UPLOAD=true
[[ "${2:-}" == "--no-upload" ]] && UPLOAD=false

fail() { echo "error: $*" >&2; exit 1; }

[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "usage: $0 vX.Y.Z [--no-upload]"
[[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]] || fail "run this on an Apple Silicon Mac"
command -v native-image >/dev/null || fail "native-image not found: install GraalVM 25 and put it on the PATH"

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

[[ -z "$(git status --porcelain)" ]] || fail "the checkout has uncommitted changes: the binary must match the tag exactly"

if $UPLOAD; then
  git fetch --quiet origin "refs/tags/$TAG:refs/tags/$TAG" || fail "tag $TAG not found on origin"
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse "$TAG^{commit}")" ]] \
    || fail "HEAD is not $TAG: run 'git checkout $TAG' first"
  gh release view "$TAG" >/dev/null 2>&1 \
    || fail "GitHub release $TAG does not exist yet: wait for the 'Build & Release Lemline' run of the tag"
fi

echo "==> Building the native binary ($(git rev-parse --short HEAD))"
rm -f lemline-runner/build/*-runner
# The build takes its version from these variables, as on a GitHub tag run; without them it
# would stamp the binary "<version>-snapshot".
GITHUB_REF="refs/tags/$TAG" GITHUB_REF_NAME="$TAG" ./gradlew --no-daemon -p lemline-runner quarkusBuild \
  -Dquarkus.native.enabled=true \
  -Dquarkus.package.jar.enabled=false

BINARY=$(ls lemline-runner/build/*-runner)

echo "==> Checking the binary"
VERSION_OUTPUT=$("$BINARY" --version)
echo "$VERSION_OUTPUT"
[[ "$VERSION_OUTPUT" == "Lemline Runner ${TAG#v}" ]] || fail "'lemline --version' does not report ${TAG#v}"

echo "==> Packaging"
OUT="$ROOT/build/release-macos"
rm -rf "$OUT" && mkdir -p "$OUT/dist/bin"
cp "$BINARY" "$OUT/dist/bin/lemline"
chmod +x "$OUT/dist/bin/lemline"
cp LICENSE.md README.md "$OUT/dist/"
ARCHIVE="lemline-$TAG-macos-arm64.tar.gz"
tar -C "$OUT/dist" -czf "$OUT/$ARCHIVE" .
(cd "$OUT" && shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256")
echo "$OUT/$ARCHIVE"
cat "$OUT/$ARCHIVE.sha256"

if $UPLOAD; then
  echo "==> Uploading to the $TAG release"
  gh release upload "$TAG" "$OUT/$ARCHIVE" "$OUT/$ARCHIVE.sha256" --clobber
  echo "Done: https://github.com/lemline/lemline/releases/tag/$TAG"
else
  echo "Not uploaded (--no-upload)."
fi
