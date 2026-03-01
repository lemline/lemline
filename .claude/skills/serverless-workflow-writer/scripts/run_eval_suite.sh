#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="$ROOT/scripts/validate_workflow.py"

echo "[1/4] Positive templates and evals"
for f in "$ROOT"/assets/templates/*.yaml "$ROOT"/assets/evals/workflow-*.yaml; do
  "$VALIDATOR" "$f" --profile spec-strict >/dev/null
  "$VALIDATOR" "$f" --profile lemline-compatible >/dev/null
done
echo "  OK"

echo "[2/4] Negative evals (must fail in spec-strict)"
for f in "$ROOT"/assets/evals/negative/*.yaml; do
  if "$VALIDATOR" "$f" --profile spec-strict >/dev/null 2>&1; then
    echo "  FAILED: expected negative case to fail: $f"
    exit 1
  fi
done
echo "  OK"

echo "[3/4] Profile-specific behavior"
PROFILE_FILE="$ROOT/assets/evals/profile/openapi-spec-only.yaml"
"$VALIDATOR" "$PROFILE_FILE" --profile spec-strict >/dev/null
if "$VALIDATOR" "$PROFILE_FILE" --profile lemline-compatible >/dev/null 2>&1; then
  echo "  FAILED: expected lemline-compatible profile to reject openapi"
  exit 1
fi
echo "  OK"

echo "[4/4] Done"
echo "Evaluation suite passed."

