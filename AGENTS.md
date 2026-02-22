<!-- Updated: 2026-02-22 -->

# AGENTS.md

Single source of truth for AI coding agents in the Lemline repository.

## Language

Use English in code, comments, docs, commit messages, and PR text.

## Project Snapshot

Lemline is an event-driven workflow orchestration runtime implementing Serverless Workflow DSL v1.0.

- Stack: Kotlin 2.2.10, Java 17, Quarkus 3.x
- Runtime model: stateless workers, workflow state carried in messages
- Persistence model: outbox pattern for durable operations
- Messaging model: dual-channel (`commands-*` and `events-*`)

## Documentation Map

Use these docs as primary context before broad refactors:

- System architecture: [`docs/orchestrator-architecture.md`](docs/orchestrator-architecture.md), [`docs/runner-architecture.md`](docs/runner-architecture.md)
- Messaging architecture ADR: [`docs/adr/0003-messaging-architecture.md`](docs/adr/0003-messaging-architecture.md)
- Workflow execution model ADR: [`docs/adr/0002-workflow-execution-model.md`](docs/adr/0002-workflow-execution-model.md)
- Database strategy ADR: [`docs/adr/0004-database-storage-strategy.md`](docs/adr/0004-database-storage-strategy.md)
- Error handling ADR: [`docs/adr/0005-error-handling-approach.md`](docs/adr/0005-error-handling-approach.md)
- Config strategy ADR: [`docs/adr/0007-config-strategy.md`](docs/adr/0007-config-strategy.md)
- Protobuf messaging rules: [`docs/protobuf-messaging.md`](docs/protobuf-messaging.md)
- Logging strategy: [`docs/logging-strategy.md`](docs/logging-strategy.md)
- CLI strategy: [`docs/cli-strategy.md`](docs/cli-strategy.md)
- Listen behavior reference: [`docs/listen-task.md`](docs/listen-task.md)
- ADR index: [`docs/adr/README.md`](docs/adr/README.md)

## Repository Layout

```text
lemline/
├── lemline-common/          # Shared utilities (IDV7, JSON, logging)
├── lemline-core/            # DSL parsing, processors, orchestrators, workflow state
├── lemline-messages-proto/  # Internal protobuf schemas + generated types
├── lemline-runner-common/   # Shared infra (outbox, cleaners, repositories, codecs)
├── lemline-runner-gateway/  # gRPC ingress gateway (start/watch APIs, auth, gateway outbox)
├── lemline-runner-analytics/ # Lifecycle analytics sink (CloudEvents -> analytics PostgreSQL)
├── lemline-runner/          # Runtime bootstrap, messaging handlers/subscribers
├── lemline-runner-cli/      # CLI commands (Picocli)
├── lemline-runner-*/        # Other feature modules (waits, retries, parents, forks, listeners, etc.)
├── docs/adr/                # Architecture decisions
└── examples/                # Local dev examples
```

## Core Architecture Rules

### Dual-channel processing

1. Commands channel (`commands-in/out`) is for high-throughput stateless execution.
2. Events channel (`events-out`) is for durable workflows operations needing DB guarantees.

### Exception-driven orchestration

Control flow in orchestration relies on specific exceptions:

- `WaitStartedException`
- `TaskRetriedException`
- `RunWorkflowStartedException`

Do not replace this pattern casually; it is part of runtime semantics.

### Stateless workflow state

`InstanceMessage` carries serialized workflow state so any worker can continue execution.

## Non-negotiable Engineering Rules

1. Use Kotlin coroutines (`suspend`) for async flows.
2. Do not introduce Mutiny (`Uni`, `Multi`) in application/repository code.
3. Do not use Hibernate ORM/Panache; use repositories with native SQL.
4. All persistence schema changes must be Flyway migrations.
5. Persistence changes must consider PostgreSQL, MySQL, and H2.
6. Use `IDV7` for entity IDs.
7. Keep domain models decoupled from transport types (protobuf classes).

## Protobuf Messaging Rules

Internal schemas are under `lemline-messages-proto/src/main/proto/internal/`.

1. Protobuf is the canonical internal messaging contract.
2. Transport uses protobuf binary; DB payloads use ProtoJSON.
3. Never reuse field numbers.
4. If removing a field, reserve both its number and name.
5. Enums must include `*_UNSPECIFIED = 0`.
6. Enum value names must satisfy Buf enum-prefix conventions.
7. Use `optional` for presence-sensitive scalar fields.
8. Keep mapping exhaustive in `*ProtobufMapper` classes.

See `docs/protobuf-messaging.md` for full policy/checklist.

## Module Patterns

Most `lemline-runner-*` feature modules follow an outbox pattern:

- `*Service.kt`: business logic
- `*Model.kt`: persistence model
- `*Repository.kt`: SQL operations (often with `FOR UPDATE SKIP LOCKED`)
- `*Outbox.kt`: outbox processor
- `*Cleaner.kt`: retention cleanup

Known deviations:

- `lemline-runner-definitions`: CRUD-centric, no outbox relay
- `lemline-runner-failures`: terminal storage, no outbox relay
- `lemline-runner-listeners`: multiple specialized outboxes
- `lemline-runner-gateway`: gRPC/API module with gateway outbox + analytics read-side
- `lemline-runner-analytics`: sink-only analytics ingestion (no outbox relay)

## High-Value File Locations

- Orchestration entry points:
  - `lemline-core/src/main/kotlin/com/lemline/core/orchestrator/StepByStepOrchestrator.kt`
  - `lemline-core/src/main/kotlin/com/lemline/core/orchestrator/FullOrchestrator.kt`
- Processor contract:
  - `lemline-core/src/main/kotlin/com/lemline/core/processors/NodeProcessor.kt`
- Runner command/event handling:
  - `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/commands/WorkflowCommandHandler.kt`
  - `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/events/WorkflowEventHandler.kt`
- Common outbox base:
  - `lemline-runner-common/src/main/kotlin/com/lemline/runner/common/outbox/AbstractOutbox.kt`
- Gateway ingress + streaming:
  - `lemline-runner-gateway/src/main/kotlin/com/lemline/runner/gateway/grpc/WorkflowGatewayGrpcService.kt`
  - `lemline-runner-gateway/src/main/kotlin/com/lemline/runner/gateway/start/WorkflowStartService.kt`
  - `lemline-runner-gateway/src/main/kotlin/com/lemline/runner/gateway/watch/WorkflowWatchService.kt`
- Analytics ingestion:
  - `lemline-runner-analytics/src/main/kotlin/com/lemline/runner/analytics/LifecycleAnalyticsSubscriber.kt`
  - `lemline-runner-analytics/src/main/kotlin/com/lemline/runner/analytics/LifecycleAnalyticsRepository.kt`
- Protobuf contracts:
  - `lemline-messages-proto/src/main/proto/internal/workflow/commands.proto`
  - `lemline-messages-proto/src/main/proto/internal/workflow/events.proto`

## Build and Test Commands

```bash
# Build
./gradlew build
./gradlew :lemline-core:build

# Test
./gradlew test
./gradlew :lemline-runner:test
./gradlew :lemline-runner-gateway:test
./gradlew :lemline-runner-analytics:test
./gradlew test --tests "*RepositoryTest"

# Protobuf
./gradlew :lemline-messages-proto:build

# Format
./gradlew spotlessCheck
./gradlew spotlessApply

# Dev
./gradlew :lemline-runner:quarkusDev
```

## Test Execution Policy for Agents

Always capture full test logs to file.

```bash
./gradlew :lemline-runner-listeners:test --tests "*ListenerRepository*" 2>&1 | tee /tmp/lemline-listeners-repo.log
```

Then inspect that file (`grep`, `tail`) instead of rerunning immediately.

Suggested naming:

- `/tmp/lemline-<module>-<topic>.log`

When changing repositories or SQL, run coverage against PostgreSQL, MySQL, and H2.

## SQL and Data Notes

Use null-safe equality per database:

```sql
-- PostgreSQL
col IS NOT DISTINCT FROM $1

-- MySQL
col <=> ?
```

## Practical Change Checklist

Before opening/updating a PR:

1. Update code + tests together (especially mapper and serialization paths).
2. Run the smallest relevant test slice, then broader suite as needed.
3. If `.proto` changed, run `buf lint` in `lemline-messages-proto/`.
4. Verify no accidental API/schema breakage.
5. Keep commits focused and messages explicit.

## Structural Peculiarities

1. No root `build.gradle.kts`; build is driven via convention plugins in `buildSrc`.
2. Very granular runner feature modules.
3. CLI is separated in `lemline-runner-cli`.
4. Two common modules exist: `lemline-common` and `lemline-runner-common`.
