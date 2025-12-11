# Implementation Plan: End-to-End Testing Framework

**Branch**: `001-testing-architecture` | **Date**: 2025-12-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-testing-architecture/spec.md`

## Summary

Build an end-to-end testing framework with two components: (1) CLI test mode in `lemline-runner` providing
`--test-mode` flag with `TestActivityExecutor` for mock responses, and (2) `lemline-testing` module as a test harness
that spawns the native-compiled runner, manages Testcontainers infrastructure, and provides CloudEvent capture/delivery
for workflow verification. Tests define workflows via CLI, start workflows via CLI, and verify behavior by reading
lifecycle CloudEvents from the broker.

## Technical Context

**Language/Version**: Kotlin 2.2.10 + Java 17
**Primary Dependencies**:
- `lemline-runner`: Quarkus 3.x (CLI with `--test-mode`), existing `ActivityExecutor` interface
- `lemline-testing`: Kotest 5.9.1, Testcontainers, Kafka/RabbitMQ client libraries, CloudEvents SDK
**Storage**: PostgreSQL, MySQL (via Testcontainers)
**Testing**: Kotest + JUnit 5, native runner binary spawned as external process
**Target Platform**: JVM (Linux/macOS/Windows server)
**Project Type**: Multi-module Gradle project (extend `lemline-runner` + new `lemline-testing` module)
**Performance Goals**: Complete workflow test in <30 seconds (excluding infrastructure startup)
**Constraints**: No `Thread.sleep()` for synchronization, deterministic execution, 99% test reliability
**Scale/Scope**: 14 task types, 4 infrastructure combinations, 100% lemline-core test case compatibility

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Code Quality First | ✅ PASS | Module follows existing patterns, Kotlin conventions |
| II. Comprehensive Testing | ✅ PASS | This feature IS the testing framework |
| III. User Experience Consistency | ✅ PASS | Consistent test API, clear error messages (FR-013) |
| IV. Performance by Design | ✅ PASS | Event-based sync avoids polling; <30s target defined |
| V. Simplicity and Minimalism | ✅ PASS | Single module, composable profiles, no over-engineering |

**Quality Gates Compliance:**
- Pre-commit: Linting/formatting will apply to new module
- CI Pipeline: Tests run on all supported databases via profiles
- Coverage: >80% for test infrastructure code
- Performance: 30-second target aligns with constitution standards
- Documentation: OpenAPI not applicable (test module, no REST endpoints)

## Project Structure

### Documentation (this feature)

```text
specs/001-testing-architecture/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (internal APIs)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
lemline-runner/                         # EXTENDED with test mode
├── src/main/kotlin/com/lemline/runner/
│   ├── activities/
│   │   └── TestActivityExecutor.kt     # Mock activity responses (--test-mode)
│   └── cli/
│       └── ListenCommand.kt            # Extended with --test-mode, --mock-config flags
└── src/test/kotlin/com/lemline/runner/
    └── activities/
        └── TestActivityExecutorTest.kt # Unit tests for mock config parsing

lemline-testing/                        # NEW MODULE (test harness)
├── build.gradle.kts                    # Module dependencies (NO runner dependency)
├── src/
│   └── main/kotlin/com/lemline/testing/
│       ├── TestWorkflowExecutor.kt     # Spawns runner, manages Testcontainers lifecycle
│       ├── RunnerProcess.kt            # Native binary process management
│       ├── CloudEventCapture.kt        # Subscribe to broker, capture events
│       ├── CloudEventDelivery.kt       # Publish events to trigger listen tasks
│       ├── WorkflowStateHooks.kt       # await* utilities on captured events
│       ├── TestConfiguration.kt        # Generate runner config + mock files
│       └── infrastructure/             # Testcontainers setup
│           ├── KafkaContainer.kt
│           ├── RabbitMQContainer.kt
│           ├── PostgresContainer.kt
│           └── MySQLContainer.kt
└── src/test/kotlin/com/lemline/testing/
    ├── SelfTest.kt                     # Framework self-validation tests
    └── e2e/                            # E2E tests (requires pre-built native binary)
        ├── SetTaskE2ETest.kt
        ├── ForkTaskE2ETest.kt
        ├── ListenTaskE2ETest.kt
        ├── EmitTaskE2ETest.kt
        ├── WaitTaskE2ETest.kt
        └── ... (one per task type)
```

**Structure Decision**: Two-part architecture:
1. `lemline-runner` extended with `--test-mode` CLI flag and `TestActivityExecutor`
2. `lemline-testing` module as test harness - spawns native runner, manages infrastructure, captures/emits CloudEvents
The harness does NOT depend on `lemline-runner` (runner is external process).

**E2E Test Location**: E2E tests live in `lemline-testing/src/test/e2e/` (not in runner) to avoid
circular dependency. The native binary must be built before E2E tests can run. CI pipeline should:
1. Build `lemline-runner` native binary
2. Run `lemline-testing` tests (which spawn the pre-built binary)

## Complexity Tracking

No constitution violations requiring justification. The design follows existing patterns:
- Extends existing `ActivityExecutor` interface for test mode
- Native binary spawning is standard process management
- CloudEvent-based sync uses existing broker infrastructure
- `lemline-testing` is thin harness, not complex framework

## Phase 0: Research Summary

See [research.md](./research.md) for detailed findings.

**Key Decisions:**
1. **Architecture**: Native binary orchestration - tests spawn runner as external process, interact via CloudEvents
2. **Activity mocking**: CLI `--test-mode` flag in runner activates `TestActivityExecutor`, mock responses via
   `--mock-config=<path>` file
3. **Module structure**: `lemline-runner` extended with test mode + new `lemline-testing` harness module
4. **Infrastructure**: `lemline-testing` manages Testcontainers lifecycle (broker + database)
5. **Event synchronization**: `CloudEventCapture` subscribes to broker, `WorkflowStateHooks` provides `await*` methods
6. **Workflow control**: Define workflows via `definition` CLI, start via `instance` CLI, verify via captured CloudEvents

## Phase 1: Design Artifacts

- **data-model.md**: Key entities and their relationships
- **contracts/**: Internal API contracts:
  - `TestActivityExecutor.kt` (in lemline-runner) - mock activity responses
  - `TestWorkflowExecutor.kt` (in lemline-testing) - test orchestration
  - `CloudEventCapture.kt` / `CloudEventDelivery.kt` - broker interaction
  - `WorkflowStateHooks.kt` - await utilities
- **quickstart.md**: Getting started guide for writing tests

## Dependencies

```kotlin
// lemline-testing/build.gradle.kts
dependencies {
    // Internal modules - NO lemline-runner dependency (spawned as external process)
    implementation(project(":lemline-core"))  // For workflow definition parsing

    // Testcontainers (infrastructure management)
    implementation(platform(libs.testcontainers.bom))
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:kafka")
    implementation("org.testcontainers:rabbitmq")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:mysql")

    // Kafka/RabbitMQ clients (for CloudEvent capture/delivery)
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.rabbitmq:amqp-client")

    // Kotest
    implementation(libs.kotest.runner.junit5)
    implementation(libs.kotest.assertions.core)

    // CloudEvents SDK (for capture/delivery)
    implementation(libs.cloudevents.core)
    implementation(libs.cloudevents.kafka)  // CloudEvent Kafka binding
}

// lemline-runner/build.gradle.kts (additions for test mode)
dependencies {
    // TestActivityExecutor mock config parsing
    implementation(libs.kaml)  // YAML parsing for --mock-config
}
```

## Migration Path

1. **Extend `lemline-runner` with test mode**:
   - Add `TestActivityExecutor` implementing `ActivityExecutor` interface
   - Add `--test-mode` and `--mock-config` CLI flags to `listen` command
   - Wire CDI to select executor based on test mode flag
   - Add unit tests for mock config parsing in `lemline-runner/src/test/`

2. **Create `lemline-testing` module**:
   - `TestWorkflowExecutor`: Testcontainers lifecycle + runner process management
   - `RunnerProcess`: Spawn native binary with CLI args
   - `CloudEventCapture`/`CloudEventDelivery`: Broker interaction
   - `WorkflowStateHooks`: `await*` utilities
   - `SelfTest`: Framework self-validation (can use JVM runner initially)

3. **Add E2E tests in `lemline-testing/src/test/e2e/`**:
   - One test class per task type (14 total)
   - Tests require pre-built native binary

4. **Configure CI pipeline**:
   - Stage 1: Build `lemline-runner` native binary
   - Stage 2: Run `lemline-testing` tests (spawns native binary)
   - Run E2E tests across all 4 infrastructure combinations
