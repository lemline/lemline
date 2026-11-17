# Jackson runtime benchmark results

Measured on 2026-11-17 on an Apple M4 Pro, 48 GiB RAM, macOS aarch64, OpenJDK 17.0.13. Both versions use the same machine, JVM version, heap flags, payload, expressions, workflow, logging settings, warmup, and measurement windows. The baseline is commit `bba9a3f6` (SDK 7.35.3 before the JSON migration). The migrated measurements include input/scope copying, numeric normalization, and unchanged Protobuf encoding.

Reproduce with `scripts/benchmark-json-runtime.sh /tmp/lemline-json-benchmark`. See [the protocol and limitations](../jackson-runtime.md#benchmark). Each row reports the median of 15 measurements (five windows in each of three fresh JVMs). All four cases reduce both elapsed time per operation and current-thread allocations.

| Workload | Before µs/op | After µs/op | Speedup | Before bytes/op | After bytes/op |
|---|---:|---:|---:|---:|---:|
| jq-object-3-expressions | 109.6 | 31.6 | 3.47x | 697000 | 303304 |
| jq-scalar-100-items | 43.5 | 17.2 | 2.53x | 281664 | 150242 |
| workflow-8-sets | 1694.3 | 240.3 | 7.05x | 9727607 | 1784994 |
| workflow-8-sets-protobuf | 8357.1 | 6572.1 | 1.27x | 38081157 | 29921859 |

The measured speedup is specific to these workloads. The Protobuf case performs binary round trips at every resume but does not involve a broker or database. No claim is made about production throughput or tail latency. Allocation counters cover the calling thread rather than the whole JVM, and this focused harness does not replace a JMH or end-to-end load test.

Raw measurements: [before](jackson-runtime-before.json), [after](jackson-runtime-after.json).
