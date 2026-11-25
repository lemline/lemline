#!/bin/bash
# Build one Writerside instance the way .github/workflows/build-docs.yml does, from a copy of lemline-docs.
#
#   writerside_build.sh <instance-id> <out-dir> [--remove-image]
#
#   instance-id   lemline (lemline.tree) or docs (docs.tree): the id in lemline-docs/writerside.cfg
#   out-dir       receives writerside-build.log, report.json and webHelp<ID>2-all.zip
#   --remove-image  delete the builder image afterwards (it is several GB)
#
# The builder version is read from build-docs.yml. Needs Docker: run outside any sandbox that blocks its socket.
set -u
instance=${1:?instance id (lemline or docs)}
out=${2:?output directory}
remove=${3:-}
repo=$(git rev-parse --show-toplevel)
version=$(sed -n "s/^ *DOCKER_VERSION: *'\([^']*\)'.*/\1/p" "$repo/.github/workflows/build-docs.yml")
image="jetbrains/writerside-builder:${version:?no DOCKER_VERSION in build-docs.yml}"
grep -q "id=\"$instance\"" "$repo/lemline-docs/writerside.cfg" "$repo"/lemline-docs/*.tree \
  || { echo "unknown instance $instance"; exit 2; }

work=$(mktemp -d "${TMPDIR:-/tmp}/writerside-XXXXXX")
mkdir -p "$out"
out=$(cd "$out" && pwd)
cp -R "$repo/lemline-docs" "$work/lemline-docs"
docker run --rm -v "$work:/github/workspace" "$image" /bin/bash -c \
  "export DISPLAY=:99.0 && (Xvfb :99 &) && /opt/builder/bin/idea.sh helpbuilderinspect --source-dir /github/workspace --product lemline-docs/$instance --runner github --output-dir /github/workspace/artifacts/" \
  > "$out/writerside-build.log" 2>&1
status=$?
echo "docker exit=$status" >> "$out/writerside-build.log"
cp "$work"/artifacts/report.json "$work"/artifacts/webHelp*-all.zip "$out/" 2>/dev/null
rm -rf "$work"
[ "$remove" = "--remove-image" ] && docker image rm "$image" >/dev/null
ls "$out"
exit $status
