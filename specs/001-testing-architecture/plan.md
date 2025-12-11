# Implementation Plan: End-to-End Testing Framework

**Branch**: `001-testing-architecture` | **Date**: 2025-12-11 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/001-testing-architecture/spec.md`

## Summary

Build a dedicated `lemline-testing` module providing a comprehensive end-to-end testing framework for Lemline workflows. The framework enables testing against real infrastructure (Kafka/RabbitMQ × PostgreSQL/MySQL) using composable Quarkus test profiles, deterministic event-based synchronization, and mocked activity execution for reproducible tests.

## Technical Context

**Language/Version**: Kotlin 2.2.10 + Java 17
**Primary Dependencies**: Quarkus 3.x, Kotest 5.9.1, Testcontainers, SmallRye Reactive Messaging, CloudEvents SDK
**Storage**: PostgreSQL, MySQL (via Testcontainers); H2 for in-memory fallback
**Testing**: Kotest + JUnit 5 (via Quarkus), Testcontainers for infrastructure
**Target Platform**: JVM (Linux/macOS/Windows server)
**Project Type**: Multi-module Gradle project (new `lemline-testing` module)
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
lemline-testing/                        # NEW MODULE
├── build.gradle.kts                    # Module dependencies
├── src/
│   └── main/kotlin/com/lemline/testing/
│       ├── TestWorkflowExecutor.kt     # Core test execution orchestration
│       ├── TestActivityExecutor.kt     # Activity mocking (HTTP, script, shell)
│       ├── CloudEventCapture.kt        # Event capture and query
│       ├── CloudEventDelivery.kt       # Programmatic event delivery
│       ├── WorkflowStateHooks.kt       # Event-based synchronization callbacks
│       ├── TestConfiguration.kt        # Test config (timeouts, mocks)
│       └── profiles/                   # Composable Quarkus profiles
│           ├── KafkaProfile.kt
│           ├── RabbitMQProfile.kt
│           ├── PostgresProfile.kt
│           ├── MySQLProfile.kt
│           └── resources/              # TestResources for Testcontainers
│               ├── KafkaTestResource.kt
│               ├── RabbitMQTestResource.kt
│               ├── PostgresTestResource.kt
│               └── MySQLTestResource.kt
└── src/test/kotlin/com/lemline/testing/
    └── SelfTest.kt                     # Framework self-validation tests

lemline-runner/
└── src/test/kotlin/com/lemline/runner/
    └── e2e/                            # End-to-end tests using lemline-testing
        ├── SetTaskE2ETest.kt
        ├── ForkTaskE2ETest.kt
        ├── ListenTaskE2ETest.kt
        ├── EmitTaskE2ETest.kt
        ├── WaitTaskE2ETest.kt
        └── ... (one per task type)
```

**Structure Decision**: New `lemline-testing` module alongside existing `lemline-common`, `lemline-core`, `lemline-runner`. The module provides testing infrastructure as a library that can be consumed by `lemline-runner` tests and external projects.

## Complexity Tracking

No constitution violations requiring justification. The design follows existing patterns:
- Single new module (not exceeding project count)
- Composable profiles follow Quarkus best practices
- Event-based sync is standard reactive pattern
- No new abstractions beyond what's specified

## Phase 0: Research Summary

See [research.md](./research.md) for detailed findings.

**Key Decisions:**
1. **Module structure**: Dedicated `lemline-testing` module with Gradle `java-test-fixtures` plugin
2. **Profile composition**: Use `@QuarkusTestProfile` interface with merged config maps
3. **Event synchronization**: Leverage existing `on*Test` callbacks + `CompletableFuture`/`Channel`
4. **Activity mocking**: Extend existing `ActivityRunner` interface with test implementation
5. **CloudEvent capture**: Hook into existing event emission via callbacks

## Phase 1: Design Artifacts

- **data-model.md**: Key entities and their relationships
- **contracts/**: Internal API contracts (TestWorkflowExecutor, TestActivityExecutor interfaces)
- **quickstart.md**: Getting started guide for writing tests

## Dependencies

```kotlin
// lemline-testing/build.gradle.kts
dependencies {
    // Internal modules
    implementation(project(":lemline-core"))
    implementation(project(":lemline-runner"))

    // Quarkus test infrastructure
    implementation("io.quarkus:quarkus-junit5")
    implementation(platform(libs.testcontainers.bom))
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:kafka")
    implementation("org.testcontainers:rabbitmq")
    implementation("org.testcontainers:postgresql")
    implementation("org.testcontainers:mysql")

    // Kotest
    implementation(libs.kotest.runner.junit5)
    implementation(libs.kotest.assertions.core)

    // CloudEvents SDK (for capture/delivery)
    implementation(libs.cloudevents.core)
}
```

## Migration Path

1. Extract existing test infrastructure from `lemline-runner/src/test`:
   - `BrokerWorkflowTestExecutor` → `TestWorkflowExecutor`
   - `KafkaTestResource`, etc. → composable profile resources
   - Test callbacks → `WorkflowStateHooks`

2. Create new `lemline-testing` module with extracted + enhanced code

3. Migrate existing runner tests to use new module

4. Add comprehensive task type coverage tests
