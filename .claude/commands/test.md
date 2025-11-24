Please create comprehensive tests for: $ARGUMENTS

## lemline-core Tests (Kotlin Test + Coroutines)

**Location & Naming:**
- Location: `lemline-core/src/test/kotlin/com/lemline/core/tests/`
- Run: `./gradlew :lemline-core:test`
- Debug: `./gradlew :lemline-core:test --tests "YourTestClass" --info`

**Basic test structure:**
```kotlin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorkflowProcessorTest {

    @Test
    fun `should execute simple workflow`() = runTest {
        val definition = """
            document:
              dsl: 1.0.0
              namespace: test
              name: simple-workflow
              version: 1.0.0
            do:
              - setData:
                  set:
                    result: Hello World
        """.trimIndent()

        val processor = getWorkflowProcessor(definition, emptyMap())
        processor.run()

        assertEquals("Hello World", processor.output["result"])
    }

    @Test
    fun `should handle task with input transformation`() = runTest {
        val definition = """
            document:
              dsl: 1.0.0
              namespace: test
              name: transform-workflow
              version: 1.0.0
            do:
              - processData:
                  input:
                    from: "\${ .input.userId }"
                  set:
                    processed: true
        """.trimIndent()

        val input = mapOf("userId" to 123)
        val processor = getWorkflowProcessor(definition, input)
        processor.run()

        assertEquals(true, processor.output["processed"])
    }
}
```

**Test categories:**
- Data flow: `DataFlowTest.kt` (input/output transformations, JQ expressions)
- Control flow: `ControlFlowTest.kt` (if, switch, goto, for)
- Error handling: `ErrorHandlingTest.kt` (try/catch/retry)
- Activities: `ActivitiesTest.kt` (HTTP, Shell, Script tasks)
- Task types: `ForkTest.kt`, `WaitTest.kt`, `RunWorkflowTest.kt`

**Core test checklist:**
- Test happy path execution
- Test JQ expression evaluation
- Test error scenarios (invalid expressions, missing data)
- Test state transitions (PENDING → RUNNING → COMPLETED/FAULTED)

---

## lemline-runner Tests (Kotest + QuarkusTest)

**Location & Naming:**
- Location: `lemline-runner/src/test/kotlin/com/lemline/runner/tests/`
- Run: `./gradlew :lemline-runner:test`
- Run specific: `./gradlew :lemline-runner:test --tests "YourTestClass"`

**Test profiles:**
```kotlin
// PostgreSQL profile
class PostgresProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "postgres"
}

// MySQL profile
class MysqlProfile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "mysql"
}

// H2 profile (default, in-memory)
class H2Profile : QuarkusTestProfile {
    override fun getConfigProfile(): String = "h2"
}
```

**Basic test structure with Kotest:**
```kotlin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import com.lemline.common.IDV7

@QuarkusTest
@TestProfile(PostgresProfile::class)
class WaitRepositoryTest : FunSpec({

    @Inject
    lateinit var waitRepository: WaitRepository

    test("should insert and find wait by UUID") {
        val wait = WaitOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            namespace = "test",
            name = "test-workflow",
            version = "1.0.0",
            instanceId = IDV7.generate(),
            delayedUntil = Instant.now().plusSeconds(60),
            status = OutboxStatus.PENDING
        )

        waitRepository.insert(wait)
        val found = waitRepository.findByUUID(wait.id)

        found shouldNotBe null
        found?.id shouldBe wait.id
        found?.status shouldBe OutboxStatus.PENDING
    }

    test("should find pending waits for processing") {
        val wait = WaitOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            namespace = "test",
            name = "test-workflow",
            version = "1.0.0",
            instanceId = IDV7.generate(),
            delayedUntil = Instant.now().minusSeconds(10), // Due now
            status = OutboxStatus.PENDING
        )

        waitRepository.insert(wait)
        val pending = waitRepository.findEntitiesToProcess(limit = 10)

        pending.any { it.id == wait.id } shouldBe true
    }

    test("should update status to SENT") {
        val wait = WaitOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            namespace = "test",
            name = "test-workflow",
            version = "1.0.0",
            instanceId = IDV7.generate(),
            delayedUntil = Instant.now(),
            status = OutboxStatus.PENDING
        )

        waitRepository.insert(wait)
        waitRepository.updateStatus(wait.id, OutboxStatus.SENT)

        val updated = waitRepository.findByUUID(wait.id)
        updated?.status shouldBe OutboxStatus.SENT
    }
})
```

**Testing suspend functions:**
```kotlin
// All repository methods are suspend functions
// Kotest handles coroutines naturally

test("should batch insert entities") {
    val entities = (1..10).map {
        RetryOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            // ... fields
        )
    }

    retryRepository.insertBatch(entities)

    entities.forEach { entity ->
        val found = retryRepository.findByUUID(entity.id)
        found shouldNotBe null
    }
}
```

**Test with different databases:**
```kotlin
// Run tests with PostgreSQL
@QuarkusTest
@TestProfile(PostgresProfile::class)
class PostgresRepositoryTest : FunSpec({ /* tests */ })

// Run same tests with MySQL
@QuarkusTest
@TestProfile(MysqlProfile::class)
class MysqlRepositoryTest : FunSpec({ /* tests */ })

// Run same tests with H2 (fast, in-memory)
@QuarkusTest
@TestProfile(H2Profile::class)
class H2RepositoryTest : FunSpec({ /* tests */ })
```

---

## Outbox Pattern Testing

**Test outbox processing lifecycle:**
```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class OutboxProcessingTest : FunSpec({

    @Inject
    lateinit var waitRepository: WaitRepository

    @Inject
    lateinit var waitOutbox: WaitOutbox

    test("should process pending waits") {
        // Arrange: Create a due wait
        val wait = WaitOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            namespace = "test",
            name = "test-workflow",
            version = "1.0.0",
            instanceId = IDV7.generate(),
            delayedUntil = Instant.now().minusSeconds(10),
            status = OutboxStatus.PENDING
        )
        waitRepository.insert(wait)

        // Act: Process outbox
        waitOutbox.processOutbox()

        // Assert: Status should be SENT
        val processed = waitRepository.findByUUID(wait.id)
        processed?.status shouldBe OutboxStatus.SENT
    }

    test("should increment attempt count on failure") {
        val retry = RetryOutboxModel(
            id = IDV7.generate(),
            workflowId = IDV7.generate(),
            // ... fields with invalid data to cause failure
            attemptCount = 0
        )
        retryRepository.insert(retry)

        // Simulate failed processing
        retryOutbox.processOutbox()

        val updated = retryRepository.findByUUID(retry.id)
        updated?.attemptCount shouldBe 1
    }

    test("should respect FOR UPDATE SKIP LOCKED") {
        // Create multiple pending entities
        val entities = (1..5).map {
            WaitOutboxModel(
                id = IDV7.generate(),
                // ... fields
                status = OutboxStatus.PENDING
            )
        }
        entities.forEach { waitRepository.insert(it) }

        // Process with limit
        val processed = waitRepository.findEntitiesToProcess(limit = 2)

        // Should only get 2 entities
        processed.size shouldBe 2
    }
})
```

---

## Workflow Execution Testing

**Test exception-driven control flow:**
```kotlin
@QuarkusTest
@TestProfile(PostgresProfile::class)
class StepByStepRunnerTest : FunSpec({

    @Inject
    lateinit var stepByStepRunner: StepByStepRunner

    @Inject
    lateinit var waitRepository: WaitRepository

    test("should create wait entry when WaitStartedException thrown") {
        val definition = """
            document:
              dsl: 1.0.0
              namespace: test
              name: wait-workflow
              version: 1.0.0
            do:
              - waitTask:
                  wait:
                    seconds: 60
        """.trimIndent()

        val message = createInstanceMessage(definition)

        // Run should create a wait entry
        stepByStepRunner.run(message)

        // Verify wait was created
        val waits = waitRepository.findByWorkflowId(message.workflowId)
        waits.size shouldBe 1
        waits.first().status shouldBe OutboxStatus.PENDING
    }

    test("should resume workflow after wait completes") {
        // Create a workflow that already completed waiting
        val message = createInstanceMessage(definition)
            .copy(position = listOf(0, "waitTask")) // Resume from wait task

        stepByStepRunner.run(message)

        // Verify workflow continued
        // ... assertions
    }
})
```

---

## Test Data Factories

**Create reusable test data:**
```kotlin
object TestDataFactory {

    fun createWaitModel(
        id: IDV7 = IDV7.generate(),
        workflowId: IDV7 = IDV7.generate(),
        namespace: String = "test",
        name: String = "test-workflow",
        version: String = "1.0.0",
        delayedUntil: Instant = Instant.now().plusSeconds(60),
        status: OutboxStatus = OutboxStatus.PENDING
    ) = WaitOutboxModel(
        id = id,
        workflowId = workflowId,
        namespace = namespace,
        name = name,
        version = version,
        instanceId = IDV7.generate(),
        delayedUntil = delayedUntil,
        status = status
    )

    fun createRetryModel(
        id: IDV7 = IDV7.generate(),
        workflowId: IDV7 = IDV7.generate(),
        attemptCount: Int = 0,
        maxAttempts: Int = 3
    ) = RetryOutboxModel(
        id = id,
        workflowId = workflowId,
        // ... other fields
        attemptCount = attemptCount,
        maxAttempts = maxAttempts
    )

    fun createWorkflowDefinition(
        namespace: String = "test",
        name: String = "test-workflow",
        version: String = "1.0.0",
        tasks: String = "- setData:\n    set:\n      result: done"
    ) = """
        document:
          dsl: 1.0.0
          namespace: $namespace
          name: $name
          version: $version
        do:
          $tasks
    """.trimIndent()
}
```

---

## Best Practices

**Test structure:**
- Use descriptive names: `should [expected behavior] when [condition]`
- One logical assertion per test
- Arrange-Act-Assert pattern
- Use test data factories for consistency
- Clean up in `afterEach` / `@BeforeEach`

**What to avoid:**
- Testing implementation details
- Shared mutable state between tests
- Copy-pasted test code
- Testing third-party libraries

**Test checklist:**
- [ ] Happy path (success scenarios)
- [ ] Error handling (exceptions, invalid input)
- [ ] Edge cases (empty data, max values)
- [ ] Database operations (CRUD, batch)
- [ ] State transitions (status changes)
- [ ] Concurrency (FOR UPDATE SKIP LOCKED)

**Run tests:**
```bash
# All tests
./gradlew test

# Specific module
./gradlew :lemline-core:test
./gradlew :lemline-runner:test

# Specific test class
./gradlew test --tests "WaitRepositoryTest"

# With debug output
./gradlew test --tests "YourTest" --info --stacktrace
```
