# AGENTS.md

Guidelines for AI coding agents working in the Lemline repository.

## Project Overview

Lemline is an event-driven workflow orchestration runtime implementing Serverless Workflow DSL v1.0.
Written in Kotlin 2.2.10 on Java 17 with Quarkus 3.x. Uses Kotlin coroutines for async operations.

## Build Commands

```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :lemline-core:build
./gradlew :lemline-runner:build
```

## Test Commands

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :lemline-core:test
./gradlew :lemline-runner:test

# Run specific test class
./gradlew test --tests "com.lemline.core.orchestrator.OrchestratorTest"

# Run single test method
./gradlew test --tests "com.lemline.runner.tests.SomeTest.testMethodName"

# Run with pattern matching
./gradlew :lemline-runner:test --tests "*RepositoryTest"
```

## Lint/Format Commands

```bash
# Check formatting
./gradlew spotlessCheck

# Apply formatting
./gradlew spotlessApply
```

## Code Style Guidelines

### Formatting

- **Indentation**: 4 spaces (2 spaces for JSON/YAML)
- **Line length**: 120 characters max
- **Line endings**: LF (Unix-style)
- **Final newline**: Required
- **Charset**: UTF-8

### Kotlin Conventions

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `ktlint` with IntelliJ IDEA style (`ktlint_code_style = intellij_idea`)
- No wildcard imports in Kotlin files (except scripts)

### Imports

```kotlin
// Correct: explicit imports
import com.lemline.core.processors.NodeProcessor
import kotlinx.serialization.json.JsonElement

// Avoid: wildcard imports (disabled by ktlint)
import com.lemline.core.processors.*
```

### Naming Conventions

| Element    | Convention           | Example                    |
|------------|----------------------|----------------------------|
| Classes    | PascalCase           | `NodeProcessor`            |
| Interfaces | PascalCase           | `WithIdRepository`         |
| Functions  | camelCase            | `findById`, `executeTask`  |
| Properties | camelCase            | `workflowId`, `nodeState`  |
| Constants  | SCREAMING_SNAKE_CASE | `ID_COLUMN`, `MAX_RETRIES` |
| Packages   | lowercase            | `com.lemline.core.nodes`   |

### Type Annotations

- Prefer explicit return types on public APIs
- Use nullable types (`T?`) over null checks
- Use `IDV7` for all entity identifiers (UUID v7)

```kotlin
// Correct
suspend fun findById(id: IDV7): Entity?

// Avoid
fun findById(id: String): Entity  // Missing suspend, wrong ID type
```

### Async Operations

**ALWAYS use Kotlin coroutines with `suspend` functions** - NOT Mutiny `Uni`:

```kotlin
// Correct
suspend fun findByUUID(uuid: IDV7): Model?
suspend fun insertBatch(models: List<Model>)

// Wrong - do not use Mutiny
fun findByUUID(uuid: IDV7): Uni<Model?>
```

### Error Handling

- Use sealed classes for domain errors
- Throw specific exceptions with context
- Use `WorkflowErrorType` enum for workflow errors

```kotlin
// Example error types
sealed class WorkflowError {
    data class ValidationError(val message: String) : WorkflowError()
    data class ExpressionError(val expression: String, val cause: Throwable) : WorkflowError()
}
```

### Documentation

- Document public APIs with KDoc
- Add OpenAPI descriptions for REST endpoints
- Use inline comments for complex logic

```kotlin
/**
 * Finds an entity by its unique identifier.
 *
 * @param id The unique identifier of the entity to find
 * @return The entity, or null if not found
 */
suspend fun findById(id: IDV7): Entity?
```

### License Header

All source files must include the SPDX license header:

```kotlin
// SPDX-License-Identifier: BUSL-1.1
```

## Module Structure

| Module                       | Purpose                                    |
|------------------------------|--------------------------------------------|
| `lemline-common`             | Shared utilities (logging, JSON, values)   |
| `lemline-core`               | DSL parsing, execution engine, expressions |
| `lemline-runner`             | Quarkus runtime, CLI, messaging            |
| `lemline-runner-common`      | Shared runner infrastructure               |
| `lemline-runner-definitions` | Workflow definition storage                |
| `lemline-runner-waits`       | Wait/timer outbox handling                 |
| `lemline-runner-retries`     | Retry outbox handling                      |
| `lemline-runner-parents`     | Parent-child workflow tracking             |
| `lemline-runner-forks`       | Fork/join branch management                |
| `lemline-runner-schedules`   | Scheduled workflow triggers                |
| `lemline-runner-listeners`   | Event listener management                  |

## Testing Patterns

### lemline-core Tests

Use Kotest with coroutines:

```kotlin
@ExperimentalTime
class OrchestratorTest : FunSpec() {
    init {
        test("should execute workflow") {
            val result = executeWorkflow(yaml, input)
            assertEquals(expected, result)
        }
    }
}
```

### lemline-runner Tests

Use `@QuarkusTest` with Kotest:

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class RepositoryTest : FunSpec({
    test("should find entity") {
        val result = repository.findById(id)
        assertNotNull(result)
    }
})
```

## Database Patterns

- **No ORM** - use native SQL with Kotlin coroutines
- Support PostgreSQL, MySQL, and H2
- All repositories must implement `suspend fun findById(id: IDV7): T?`
- Use `FOR UPDATE SKIP LOCKED` for outbox queries

## Key Files

- **Entry point**: `lemline-runner-cli/src/main/kotlin/.../LemlineApplication.kt`
- **Orchestrator**: `lemline-core/src/main/kotlin/.../orchestrator/`
- **Processors**: `lemline-core/src/main/kotlin/.../processors/`
- **Repositories**: `lemline-runner-*/src/main/kotlin/.../repositories/`

## Language

Always use English in code, comments, and documentation - regardless of user language.
