# Lemline Agent Guide

This file provides guidance for AI agents working on the Lemline codebase.

## Project Overview

Lemline is a high-performance, event-driven workflow orchestration runtime that implements the Serverless Workflow DSL v1.0. It runs on existing infrastructure (database + message broker) with stateless, horizontally scalable workers.

**Core Philosophy**: Minimize database usage. Workflow state is compressed and carried within broker messages. The database is only used for timers, retries, and parent-child relationships.

## Build Commands

| Action | Command |
|---|---|
| Build entire project | `./gradlew build` |
| Build `lemline-core` | `./gradlew :lemline-core:build` |
| Build `lemline-runner` | `./gradlew :lemline-runner:build` |
| Build native image (Linux) | `./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true` |
| Build native image (macOS) | `./gradlew clean :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false` |

## Test Commands

| Action | Command |
|---|---|
| Run all tests | `./gradlew test` |
| Run module tests | `./gradlew :lemline-core:test` or `./gradlew :lemline-runner:test` |
| Run specific test class | `./gradlew test --tests "com.lemline.runner.tests.YourTestClass"` |
| Run single test method | `./gradlew test --tests "com.lemline.runner.tests.YourTestClass.testMethod"` |

## Development Mode

| Action | Command |
|---|---|
| Quarkus dev mode (hot reload) | `./gradlew :lemline-runner:quarkusDev` |
| With custom config | `QUARKUS_CONFIG_LOCATIONS=application.yml ./gradlew :lemline-runner:quarkusDev` |

## Architecture & Patterns

### 1. Event-Driven Design
- **Workflow Channel**: High-throughput, stateless messages carrying compressed workflow state.
- **Database Channel**: Durable operations (timers, retries, parent tracking).

### 2. Workflow State
- **Node-Based**: Tree of `Node<T>` (definition) and `NodeInstance<T>` (runtime).
- **State Compression**: `InstanceMessage` contains current `NodePosition`, map of `NodeState`, and metadata.
- **Stateless**: Any worker can process any message.

### 3. Outbox Pattern (Crucial)
All DB writes use the outbox pattern:
1. Insert `PENDING` into outbox table (`lemline_waits`, `lemline_retries`, etc.).
2. `OutboxRelay` queries with `FOR UPDATE SKIP LOCKED`.
3. Process batch.
4. Update to `SENT` or increment `attempt_count`.

### 4. Execution Engine
- **Processor.kt**: Core engine. Maintains state machine, navigates node tree, evaluates JQ expressions.
- **StepByStepRunner.kt**: Executes one step at a time. Uses exception-driven control flow (`WaitStartedException`, `TaskRetriedException`, etc.) to trigger infrastructure actions.

## Coding Conventions

### Kotlin
- **Style**: 4 spaces indentation, max 120 chars line length.
- **Reactive**: Uses Mutiny (`Uni`, `Multi`).
- **Testing**: Uses Kotest + Mockk.

### Database (No ORM)
- **Native SQL**: Use `Repository.kt` base class.
- **Compatibility**: Must work on PostgreSQL, MySQL, and H2.
- **Pattern**:
  ```kotlin
  // Return Uni<T?> for nullables
  fun findByUUID(uuid: UUID): Uni<SomeModel?>
  
  // Use FOR UPDATE SKIP LOCKED for queues
  fun findEntitiesToProcess(limit: Int): Uni<List<Model>>
  ```

### Testing Guidelines

**Important for Reactive Testing:**
ALWAYS use `asserter.assertThat()` for `Uni` operations. NEVER use `.replaceWithVoid()`.

```kotlin
// CORRECT
asserter.assertThat(
    { repository.save(model) },
    { result -> assertNotNull(result) }
)

// INCORRECT
asserter.execute { repository.save(model).replaceWithVoid() }
```

## Important Files

- **Engine**: `lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt`
- **Runner**: `lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt`
- **Messages**: `lemline-runner/src/main/kotlin/com/lemline/runner/messaging/instances/InstanceMessageHandler.kt`
- **Outbox**: `lemline-runner/src/main/kotlin/com/lemline/runner/outbox/OutboxRelay.kt`

## Configuration (`.lemline.yaml`)

Search order: CLI args -> Env vars -> `.lemline.yaml` -> User config.

```yaml
lemline:
    database:
        type: postgresql
        postgresql:
            host: localhost
            port: 5432
            # ...
    messaging:
        type: kafka
        kafka:
            brokers: localhost:9092
            # ...
```
