#!/usr/bin/env bash
# SPDX-License-Identifier: BUSL-1.1
set -euo pipefail

repository_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)
log_file=$(mktemp "${TMPDIR:-/tmp}/lemline-rabbit-shutdown.XXXXXX")
echo "Capturing complete RabbitMQ shutdown log in $log_file"

cd "$repository_root"
./gradlew --no-build-cache :lemline-runner:cleanTest :lemline-runner:test --tests '*RabbitConnectorShutdownTest' 2>&1 | tee "$log_file"

# The callback runs after JUnit has marked the test passed. Only the complete
# Gradle process log includes CDI and Vert.x destruction and the late callback.
if grep -Eq '\[Error Occurred After Shutdown\]|RejectedExecutionException: event executor terminated' "$log_file"; then
    echo "RabbitMQ callback ran after shutdown; see $log_file" >&2
    exit 1
fi

echo "RabbitMQ shutdown completed without a late recovery callback. Log: $log_file"
