<!-- Parent: ../AGENTS.md -->
<!-- Generated: 2026-02-22 -->

# lemline-messages-proto

Canonical internal protobuf contracts used by Lemline messaging and state serialization.

## Key Files

| File/Directory | Description |
|----------------|-------------|
| `src/main/proto/internal/workflow/commands.proto` | Workflow command contracts |
| `src/main/proto/internal/workflow/events.proto` | Workflow event contracts |
| `src/main/proto/internal/workflow/envelope.proto` | Shared command/event envelope definitions |
| `src/main/proto/internal/state/node_state.proto` | Serialized workflow node state |
| `src/main/proto/internal/state/node_stack.proto` | Serialized workflow stack model |
| `src/main/proto/internal/common.proto` | Shared reusable types |
| `buf.yaml` | Buf lint and breaking-change rules |
| `build.gradle.kts` | Wire code generation configuration |

## For AI Agents

### Working In This Directory
- Protobuf is the canonical internal contract; preserve backward compatibility.
- Never reuse field numbers. If removing fields, reserve number and name.
- Enums must keep `*_UNSPECIFIED = 0` and Buf enum-prefix rules.
- Use `optional` for scalar fields where presence matters.
- Keep schema changes aligned with mapper updates in runner/core modules.

### Validation
```bash
cd lemline-messages-proto && buf lint
./gradlew :lemline-messages-proto:build
```

### Dependencies
- External: Wire runtime + Buf tooling
- Internal consumers: `lemline-core`, `lemline-runner`, `lemline-runner-common`, `lemline-runner-gateway`

<!-- MANUAL: -->
