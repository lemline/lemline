# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Language

IMPORTANT: even if instructed in another language than English, always use English in the code and all files in this
project!

## Project Overview

Lemline is a high-performance, event-driven workflow orchestration runtime that implements the Serverless Workflow DSL
v1.0. It's designed to run on existing infrastructure (your database + message broker) with stateless workers that scale
horizontally.

**Core Philosophy**: Minimize database usage by carrying workflow state within broker messages. The database is only
used when strictly necessary (timers, retries, parent-child workflow relationships).

## Build and Test Commands

### Building

```bash
# Build entire project
./gradlew build

# Build specific module
./gradlew :lemline-core:build
./gradlew :lemline-runner:build

# Build native image (Linux)
./gradlew :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false -Dquarkus.native.container-build=true

# Build native image (macOS)
./gradlew clean :lemline-runner:assemble -Dquarkus.native.enabled=true -Dquarkus.package.jar.enabled=false
```

### Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :lemline-core:test
./gradlew :lemline-runner:test

# Run specific test class
./gradlew test --tests "com.lemline.runner.tests.YourTestClass"

# Run single test method
./gradlew test --tests "com.lemline.runner.tests.YourTestClass.testMethod"
```

### Development Mode

```bash
# Run in Quarkus dev mode (hot reload)
./gradlew :lemline-runner:quarkusDev

# Run with custom config
QUARKUS_CONFIG_LOCATIONS=application.yml ./gradlew :lemline-runner:quarkusDev
```

### Running the Application

```bash
# Run JAR with default config
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar listen --info

# Run with custom config
QUARKUS_CONFIG_LOCATIONS=application.yml java -jar lemline-runner/build/quarkus-app/quarkus-run.jar listen

# Run native binary
./lemline-runner/build/lemline-runner-0.0.1-SNAPSHOT-runner listen

# Other CLI commands
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar config
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar definition
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar instance
java -jar lemline-runner/build/quarkus-app/quarkus-run.jar migrate
```

## Architecture Overview

### Module Structure

- **lemline-common**: Shared utilities and common functionality
- **lemline-core**: DSL implementation (workflow parsing, execution engine, expression evaluation)
- **lemline-runner**: Quarkus-based runtime (messaging, persistence, scheduling, CLI)
- **lemline-docs**: Documentation

### Core Architecture Patterns

#### 1. Event-Driven Dual-Channel Design

Lemline uses two separate message channels for different concerns:

- **Workflow Channel** (`commands-in` → `commands-out`): High-throughput, stateless message flow carrying compressed
  workflow state
- **Database Channel** (`events-out`): Durable operations requiring transactional guarantees (timers, retries, parent
  tracking)

This separation enables horizontal scaling without shared state.

#### 2. Workflow State Management

**Node-Based Execution Model**:

- Each workflow is a tree of `Node<T>` objects (immutable definitions)
- During execution, `NodeInstance<T>` objects manage runtime state
- `NodePosition` uniquely identifies location in the workflow tree (e.g., `[0, "taskName", "do", 1, "nestedTask"]`)

**State Compression**:

- Entire workflow state is serialized into `InstanceMessage`:
    - Current `NodePosition`
    - Map of `NodeState` objects (only non-empty states)
    - Workflow metadata (namespace, name, version, id)
- Enables stateless workers - any worker can process any message

#### 3. Step-by-Step Execution

**Key Class**: `StepByStepRunner.kt`

The runner executes workflows one step at a time using exception-driven control flow:

- **Normal step**: Returns updated `InstanceMessage` → flows to `commands-out` channel
- **Wait needed**: Throws `WaitStartedException` → saves to `lemline_waits` table
- **Retry needed**: Throws `TaskRetriedException` → saves to `lemline_retries` table with backoff
- **Child workflow**: Throws `RunWorkflowStartedException` → saves parent to `lemline_parents`, creates child message

This design separates workflow logic (in `Processor`) from infrastructure concerns (in `StepByStepRunner`).

#### 4. Outbox Pattern

All database writes go through outbox tables with the pattern:

1. Insert into outbox table with `status=PENDING`
2. Scheduled processor (`OutboxRelay`) queries with `FOR UPDATE SKIP LOCKED`
3. Process batch concurrently
4. On success: Set `status=SENT`
5. On failure: Increment `attempt_count`, calculate exponential backoff
6. Cleanup old SENT messages

Outbox tables: `lemline_waits`, `lemline_retries`, `lemline_parents`, `lemline_schedules`, `lemline_failures`

#### 5. Processor Execution Engine

**Key Class**: `Processor.kt` in lemline-core

The core workflow execution engine that:

- Maintains workflow state machine (PENDING → RUNNING → COMPLETED/FAULTED/WAITING)
- Navigates node tree (goingUp, goingDown, goingSide, skipping)
- Evaluates JQ expressions for data transformation
- Handles errors through `TryInstance` with retry/catch logic
- Provides hooks: `onTaskStarted`, `onTaskCompleted`, `onTaskRetried`

### Key Execution Flows

#### Normal Task Execution

```
InstanceMessage (commands-in)
→ InstanceMessageHandler (deserialize, load definition)
→ StepByStepRunner.run() (execute one step)
→ Processor.run() (execute task)
→ onTaskCompleted throws TaskCompletedException
→ Update InstanceMessage with new position/state
→ Emit to commands-out
```

#### Wait Task Execution

```
Processor reaches WaitInstance
→ onTaskStarted throws WaitStartedException(delay)
→ StepByStepRunner.onWait() creates WaitOutboxModel
→ Send to events-out
→ DatabaseMessageHandler inserts into lemline_waits
[Time passes...]
→ WaitOutbox scheduler finds due wait
→ Sends InstanceMessage to commands-in (continues from saved position)
```

#### Child Workflow Execution

```
Processor reaches RunInstance (RunWorkflow)
→ onTaskStarted throws RunWorkflowStartedException
→ StepByStepRunner.onRunWorkflow() creates:
  - ParentOutboxModel (parent state)
  - InstanceMessage (child workflow)
→ Send IngestionMessage to events-out
→ DatabaseMessageHandler inserts parent, sends child message
→ Child executes independently
→ On completion: CompletedMessage triggers parent continuation
→ DatabaseMessageHandler finds parent, updates with child output
→ Sends parent InstanceMessage to commands-out
```

## Database Patterns

### No ORM - Native SQL with Kotlin Coroutines

Lemline uses native SQL with Kotlin coroutines for better control and performance:

- All repositories extend `Repository.kt` base class
- **Use `suspend` functions with Kotlin coroutines** - NOT Mutiny `Uni`
- Must work across all supported databases (PostgreSQL, MySQL, H2)
- Use database-agnostic SQL where possible

### Common Patterns

```kotlin
// All repos use suspend functions returning nullable types
interface SomeRepository : WithUUIDRepository<SomeModel> {
    suspend fun findByUUID(uuid: IDV7): SomeModel?
}

// Batch operations for performance
suspend fun insertBatch(models: List<Model>)

// Outbox pattern with FOR UPDATE SKIP LOCKED
suspend fun findEntitiesToProcess(limit: Int): List<Model>
// SELECT ... WHERE status = 'PENDING'
//   AND delayed_until <= NOW()
//   AND attempt_count < maxAttempts
// FOR UPDATE SKIP LOCKED LIMIT ?
```

### Database Migrations

- Located in: `lemline-runner/src/main/resources/db/migration/{postgresql,mysql,h2}/`
- Flyway versioned migrations: `V001__initial_schema.sql`, `V002__add_feature.sql`
- Test with all supported databases before merging

## Testing Patterns

### lemline-core Testing

Uses Kotlin Test + Coroutines Test:

```kotlin
@Test
fun testWorkflow() = runTest {
        val processor = getWorkflowProcessor(yamlDef, input)
        processor.run()
        assertEquals(expected, processor.output)
    }
```

Test categories:

- Data flow: `DataFlowTest.kt` (input/output transformations)
- Control flow: `ControlFlowTest.kt` (if, switch, goto)
- Error handling: `ErrorHandlingTest.kt` (try/catch/retry)
- Activities: `ActivitiesTest.kt` (HTTP, Shell, Script)

### lemline-runner Testing

Uses Kotest with `@QuarkusTest` and Kotlin coroutines:

```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class SomeTest : FunSpec({
    test("should process workflow") {
        // Test with real DB/broker via test containers
        val result = repository.save(model)
        assertNotNull(result)
    }
})
```

IMPORTANT for Coroutine Testing:

- All repository methods are `suspend` functions
- Test functions use Kotlin coroutines naturally
- No special reactive testing utilities needed - just call suspend functions directly

## Important Implementation Details

### NodePosition Navigation

Position tokens identify special nodes:

- `DO`: Sequential do block
- `TRY`: Try block
- `CATCH`: Catch block
- `FOREACH`: For loop iteration
- `BRANCHES`: Parallel branches

Example position: `[0, "validateInput", "try", "do", 1, "callApi"]`

### IDV7 for Time-Sortable IDs

All entities use UUID v7 (time-ordered):

- Globally unique
- Sortable by creation time
- Enables efficient range queries
- Generated in application code, not database

### Expression Evaluation

Lemline uses JQ for data transformation:

- `ExpressionEvaluator.kt`: JQ engine wrapper
- `Scope.kt`: Hierarchical context (workflow, task, runtime, input, output)
- Expressions evaluated at: input transformation, output transformation, conditions, exports

### Messaging and Concurrency

**Messaging Layer**: Uses reactive streams with backpressure

- `InstanceMessageSubscriber` requests N messages (maxConcurrency)
- Processes messages asynchronously
- Requests next only after processing current
- Graceful shutdown waits for active messages with timeout

**Database Layer**: Uses Kotlin coroutines with suspend functions

- All repository methods are `suspend` functions
- Enables non-blocking I/O without reactive complexity
- Works naturally with Kotlin's structured concurrency
- `FOR UPDATE SKIP LOCKED` prevents double processing in outbox queries

## Configuration

### User Configuration Search Order

1. CLI argument: `--config=<path>`
2. Environment variable: `LEMLINE_CONFIG`
3. Current directory: `.lemline.yaml`
4. User config: `~/.config/lemline/config.yaml`
5. Home directory: `~/.lemline.yaml`

### Key Configuration Properties

```yaml
lemline:
    database:
        type: postgresql  # or mysql
        postgresql:
            host: localhost
            port: 5432
            username: postgres
            password: ${LEMLINE_PG_PASSWORD}
            name: lemline

    messaging:
        type: kafka  # or rabbitmq
        kafka:
            brokers: localhost:9092
            topic: lemline
            group-id: lemline-worker-group
```

## Code Style and Best Practices

### Kotlin Conventions

- 4 spaces for indentation
- Maximum line length: 120 characters
- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Document public APIs with KDoc

### Backend Development

- **Always add OpenAPI descriptions** when coding backend endpoints
- All repositories must have a `findByUUID` method with signature: `suspend fun findByUUID(uuid: IDV7): T?`
- Use Kotlin coroutines (`suspend` functions) for all async operations
- Use structured logging with MDC context
- Follow SOLID principles and DRY

### Security

- Never commit sensitive information (.env, credentials, API keys)
- Use environment variables for secrets
- Follow OWASP security guidelines
- Validate all inputs (input schema validation)

## Important Files Reference

### Core Entry Points

- Workflow execution engine: lemline-core/src/main/kotlin/com/lemline/core/processor/Processor.kt
- Task execution base: lemline-core/src/main/kotlin/com/lemline/core/nodes/NodeInstance.kt
- Definition management: lemline-core/src/main/kotlin/com/lemline/core/definitions/DefinitionCache.kt

### Runner Entry Points

- CLI entry point: lemline-runner/src/main/kotlin/com/lemline/runner/cli/LemlineApplication.kt
- Execution orchestrator: lemline-runner/src/main/kotlin/com/lemline/runner/StepByStepRunner.kt
- Message processor: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/instances/InstanceMessageHandler.kt

### Critical Infrastructure

- Reactive consumer base: lemline-runner/src/main/kotlin/com/lemline/runner/messaging/MessageSubscriber.kt
- Outbox processor base: lemline-runner/src/main/kotlin/com/lemline/runner/outbox/OutboxRelay.kt
- Outbox repository base: lemline-runner/src/main/kotlin/com/lemline/runner/repositories/OutboxRepository.kt

## Development Workflow

1. Create feature branch from `main`: `git checkout -b feature/your-feature-name`
2. Make changes with tests
3. Ensure all tests pass: `./gradlew test`
4. Test with all supported databases if touching persistence layer
5. Create pull request targeting `main` branch
6. Address code review feedback

## Documentation

- **Core Developer Guide**: `/lemline-core/docs/*` - Detailed workflow DSL and execution engine
- **Runner Developer Guide**: `/lemline-runner/docs/*` - Detailed runner architecture including messaging channels,
  database tables, outbox patterns, and CLI commands
- Architecture Decision Records: `/docs/adr/`
- Contributing guidelines: `CONTRIBUTING.md`
- Serverless Workflow DSL: https://serverlessworkflow.io/

## Extending Lemline

### Adding a New Task Type

1. Create model in lemline-core: `src/main/kotlin/com/lemline/core/models/tasks/`
2. Create instance in lemline-core: `src/main/kotlin/com/lemline/core/instances/`
3. Register in `Node.kt` and `NodeInstance.kt` factory methods
4. Add tests in lemline-core
5. Update documentation

### Adding a New Activity Runner

1. Implement `ActivityRunner` interface in lemline-core
2. Register in `ActivityRunnerProvider.kt`
3. Add configuration if needed
4. Add integration tests

### Adding a New Database

1. Add JDBC driver dependency to lemline-runner
2. Create migration scripts in `resources/db/migration/{database}/`
3. Update `DatabaseConfig` in `LemlineConfiguration.kt`
4. Update `toQuarkusProperties()` method
5. Add test profile and test resources
6. Test all repositories

### Adding a New Message Broker

See ADR-0003 section "Adding a New Messaging Technology" for detailed steps:

1. Add SmallRye connector dependency
2. Update `LemlineConfigConstants.kt`
3. Add broker-specific config interface
4. Update `MessagingConfig.toQuarkusProperties()`
5. Create test profile and test resources

## Active Technologies

- Kotlin 2.2.10, Java 17 + Quarkus, SmallRye Reactive Messaging, CloudEvents SDK (io.cloudevents) (002-lifecycle-events)
- N/A (fire-and-forget to messaging channel, no database persistence) (002-lifecycle-events)
- Kotlin 2.2.10 + Java 17 + Quarkus 3.x, Kotest 5.9.1, Testcontainers, SmallRye Reactive Messaging, CloudEvents SDK (
  001-testing-architecture)
- PostgreSQL, MySQL (via Testcontainers); H2 for in-memory fallback (001-testing-architecture)

- Kotlin 2.2.10, Java 17 + Kotest 5.9.1, JUnit 5 (via Quarkus), MockK 1.13.9, Kotlinx Coroutines 1.10.2
- PostgreSQL, MySQL, H2 (all supported)

## Recent Changes

- 002-lifecycle-events: Added Kotlin 2.2.10, Java 17 + Quarkus, SmallRye Reactive Messaging, CloudEvents SDK (
  io.cloudevents)
