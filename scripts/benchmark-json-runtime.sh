#!/usr/bin/env bash
# Compare the same workloads against the pre-migration runtime and this checkout.
set -euo pipefail
repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
result_dir="${1:-/tmp/lemline-json-benchmark}"
mkdir -p "$result_dir"
result_dir="$(cd "$result_dir" && pwd)"
baseline_dir="$(mktemp -d /tmp/lemline-json-baseline.XXXXXX)"
rmdir "$baseline_dir"
cleanup() { git -C "$repo_dir" worktree remove --force "$baseline_dir"; }
trap cleanup EXIT
git -C "$repo_dir" worktree add --detach "$baseline_dir" bba9a3f617b2a1824faaa1ce104613cc4f1cdc7c
python3 - "$repo_dir" "$baseline_dir" <<'PY'
from pathlib import Path
import sys
current, baseline = map(Path, sys.argv[1:])
relative = Path('lemline-core/src/test/kotlin/com/lemline/core/benchmarks/JsonRuntimeBenchmark.kt')
source = (current / relative).read_text()
source = source.replace('import com.fasterxml.jackson.databind.JsonNode\n', 'import kotlinx.serialization.json.JsonElement\n')
source = source.replace('import com.fasterxml.jackson.databind.node.ObjectNode\n', 'import kotlinx.serialization.json.JsonObject\n')
source = source.replace('import com.lemline.common.json.*\n', 'import kotlinx.serialization.json.Json\nimport kotlinx.serialization.json.JsonPrimitive\n')
source = source.replace('JsonNode', 'JsonElement').replace('ObjectNode', 'JsonObject')
source = source.replace('parseJson(', 'Json.parseToJsonElement(').replace('jsonValue(', 'JsonPrimitive(')
(baseline / relative).parent.mkdir(parents=True, exist_ok=True)
(baseline / relative).write_text(source)
build_file = Path('lemline-core/build.gradle.kts')
source_build = (current / build_file).read_text()
start = source_build.index('tasks.register<JavaExec>("jsonBenchmark")')
end = source_build.index('\n}\n', start) + 3
with (baseline / build_file).open('a') as output:
    output.write('\n' + source_build[start:end])
PY
(cd "$baseline_dir" && ./gradlew --no-daemon :lemline-core:jsonBenchmark --console=plain) > "$result_dir/before.log" 2>&1
(cd "$repo_dir" && ./gradlew --no-daemon :lemline-core:jsonBenchmark --console=plain) > "$result_dir/after.log" 2>&1
python3 - "$result_dir" <<'PY'
import json, statistics, sys
from pathlib import Path
root = Path(sys.argv[1])
results = {}
for label in ('before', 'after'):
    rows = [json.loads(line[6:]) for line in (root / f'{label}.log').read_text().splitlines() if line.startswith('BENCH ')]
    assert len(rows) == 60, f'{label}: expected 60 measurements, found {len(rows)}'
    (root / f'{label}.json').write_text(json.dumps(rows, indent=2) + '\n')
    results[label] = {name: {metric: statistics.median(row[metric] for row in rows if row['case'] == name) for metric in ('ns_per_op', 'bytes_per_op')} for name in sorted({row['case'] for row in rows})}
for case in results['before']:
    before, after = results['before'][case], results['after'][case]
    print(f"{case}: {before['ns_per_op']/1000:.1f} -> {after['ns_per_op']/1000:.1f} us/op ({before['ns_per_op']/after['ns_per_op']:.2f}x); {before['bytes_per_op']:.0f} -> {after['bytes_per_op']:.0f} bytes/op")
PY
