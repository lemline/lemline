// SPDX-License-Identifier: BUSL-1.1
/**
 * TestWorkflowExecutor Contract
 *
 * This file defines the contract for the TestWorkflowExecutor interface.
 * Implementation will be in lemline-testing module.
 *
 * Architecture: Native binary orchestration - spawns runner as external process,
 * interacts via CLI and CloudEvents through the message broker.
 *
 * @feature 001-testing-architecture
 */
package com.lemline.testing

import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * Orchestrates end-to-end workflow test execution with native runner binary.
 *
 * This executor manages the full test lifecycle:
 * 1. Start infrastructure via Testcontainers (broker + database)
 * 2. Spawn native-compiled runner with `--test-mode` flag
 * 3. Define workflows via CLI command
 * 4. Start workflow instances via CLI command
 * 5. Capture CloudEvents from broker for verification
 * 6. Emit CloudEvents to broker for listen task testing
 * 7. Stop runner process and containers on teardown
 *
 * ## Usage
 *
 * ```kotlin
 * class MyWorkflowTest : FunSpec({
 *
 *     lateinit var executor: TestWorkflowExecutor
 *
 *     beforeSpec {
 *         executor = TestWorkflowExecutor.create(
 *             broker = BrokerType.KAFKA,
 *             database = DatabaseType.POSTGRESQL
 *         )
 *         executor.start()
 *     }
 *
 *     afterSpec {
 *         executor.stop()
 *     }
 *
 *     test("workflow produces expected output") {
 *         executor.defineWorkflow(
 *             yaml = """
 *                 document:
 *                   name: my-workflow
 *                   version: "1.0.0"
 *                   dsl: "1.0.0"
 *                 do:
 *                   - set:
 *                       answer: 42
 *             """.trimIndent()
 *         )
 *
 *         val result = executor.runWorkflow(
 *             name = "my-workflow",
 *             version = "1.0.0",
 *             input = buildJsonObject { }
 *         )
 *
 *         result.output shouldBe buildJsonObject { put("answer", 42) }
 *     }
 * })
 * ```
 *
 * ## Thread Safety
 *
 * TestWorkflowExecutor is thread-safe and supports concurrent test execution.
 * Each workflow instance is isolated via unique workflow IDs.
 */
interface TestWorkflowExecutor {

    /**
     * Access to CloudEventCapture for reading events from broker.
     */
    val cloudEventCapture: CloudEventCapture

    /**
     * Access to CloudEventDelivery for emitting events to broker.
     */
    val cloudEventDelivery: CloudEventDelivery

    /**
     * Access to WorkflowStateHooks for deterministic await utilities.
     */
    val stateHooks: WorkflowStateHooks

    /**
     * Start infrastructure (Testcontainers) and spawn native runner.
     *
     * This method:
     * 1. Starts broker container (Kafka or RabbitMQ)
     * 2. Starts database container (PostgreSQL or MySQL)
     * 3. Generates runner configuration file
     * 4. Spawns native runner with `--test-mode` flag
     * 5. Waits for runner to be ready
     *
     * @throws IllegalStateException if containers fail to start
     * @throws IllegalStateException if runner fails to start
     */
    suspend fun start()

    /**
     * Stop runner process and infrastructure containers.
     *
     * This method:
     * 1. Stops the native runner process (graceful shutdown)
     * 2. Stops broker and database containers
     * 3. Cleans up temporary configuration files
     */
    suspend fun stop()

    /**
     * Define a workflow via CLI command.
     *
     * Internally executes: `./lemline-runner definition create --yaml=<path>`
     *
     * @param yaml Complete workflow YAML including document header
     * @throws WorkflowDefinitionException if CLI command fails
     */
    suspend fun defineWorkflow(yaml: String)

    /**
     * Run a workflow and wait for completion.
     *
     * Internally:
     * 1. Executes: `./lemline-runner instance start --name=<name> --version=<version> --input=<json>`
     * 2. Waits for workflow.completed or workflow.faulted CloudEvent via stateHooks
     *
     * @param name Workflow name
     * @param version Workflow version
     * @param input Workflow input as JsonElement
     * @param mocks Mock configuration (optional) - generates --mock-config file
     * @param timeout Maximum time to wait for completion
     * @return WorkflowResult with status and output
     * @throws WorkflowTimeoutException if workflow doesn't complete within timeout
     */
    suspend fun runWorkflow(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig? = null,
        timeout: Duration = Duration.parse("30s")
    ): WorkflowResult

    /**
     * Start a workflow without waiting for completion.
     *
     * Use this with [stateHooks] for manual synchronization when testing:
     * - Listen tasks that need external events delivered mid-execution
     * - Intermediate task outputs before workflow completion
     * - Complex async patterns requiring fine-grained control
     *
     * @param name Workflow name
     * @param version Workflow version
     * @param input Workflow input as JsonElement
     * @param mocks Mock configuration (optional)
     * @return Workflow instance ID
     */
    suspend fun startWorkflowAsync(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig? = null
    ): String

    companion object {
        /**
         * Create a TestWorkflowExecutor instance.
         *
         * @param broker Broker type (KAFKA or RABBITMQ)
         * @param database Database type (POSTGRESQL or MYSQL)
         * @param runnerBinaryPath Path to native runner binary (optional, auto-detected)
         * @return TestWorkflowExecutor instance (not yet started)
         */
        fun create(
            broker: BrokerType,
            database: DatabaseType,
            runnerBinaryPath: String? = null
        ): TestWorkflowExecutor = TestWorkflowExecutorImpl(broker, database, runnerBinaryPath)
    }
}

/**
 * Result of workflow execution.
 */
data class WorkflowResult(
    /**
     * Final workflow status.
     */
    val status: WorkflowStatus,

    /**
     * Workflow output (if completed successfully).
     */
    val output: JsonElement?,

    /**
     * Error details (if faulted).
     */
    val error: WorkflowError?
)

/**
 * Workflow status.
 */
enum class WorkflowStatus {
    COMPLETED,
    FAULTED,
    CANCELLED
}

/**
 * Workflow error details.
 */
data class WorkflowError(
    val type: String,
    val title: String,
    val detail: String?
)

/**
 * Mock configuration for test activity responses.
 *
 * This generates a YAML file that the runner loads via `--mock-config` flag.
 */
class MockConfig private constructor(
    internal val httpMocks: List<HttpMock>,
    internal val scriptMocks: List<ScriptMock>,
    internal val shellMocks: List<ShellMock>
) {
    class Builder {
        private val httpMocks = mutableListOf<HttpMock>()
        private val scriptMocks = mutableListOf<ScriptMock>()
        private val shellMocks = mutableListOf<ShellMock>()

        fun http(block: HttpMockBuilder.() -> Unit) {
            httpMocks.add(HttpMockBuilder().apply(block).build())
        }

        fun script(block: ScriptMockBuilder.() -> Unit) {
            scriptMocks.add(ScriptMockBuilder().apply(block).build())
        }

        fun shell(block: ShellMockBuilder.() -> Unit) {
            shellMocks.add(ShellMockBuilder().apply(block).build())
        }

        fun build() = MockConfig(httpMocks, scriptMocks, shellMocks)
    }

    companion object {
        operator fun invoke(block: Builder.() -> Unit): MockConfig =
            Builder().apply(block).build()
    }
}

// Mock builders (simplified - full implementation in lemline-testing)
class HttpMockBuilder {
    private var matcher: HttpMatcher? = null
    private var response: HttpMockResponse? = null

    fun match(block: HttpMatcher.() -> Unit) {
        matcher = HttpMatcher().apply(block)
    }

    fun respond(block: HttpMockResponse.() -> Unit) {
        response = HttpMockResponse().apply(block)
    }

    fun build() = HttpMock(matcher!!, response!!)
}

class HttpMatcher {
    var url: String? = null
    infix fun String.contains(pattern: String): Boolean = true // DSL marker
}

class HttpMockResponse {
    var status: Int = 200
    var body: JsonElement? = null
    var error: String? = null
}

data class HttpMock(val matcher: HttpMatcher, val response: HttpMockResponse)

class ScriptMockBuilder {
    fun match(block: ScriptMatcher.() -> Unit) {}
    fun respond(block: ScriptMockResponse.() -> Unit) {}
    fun build() = ScriptMock()
}

class ScriptMatcher {
    var language: String? = null
}

class ScriptMockResponse {
    var output: JsonElement? = null
}

data class ScriptMock(val placeholder: Unit = Unit)

class ShellMockBuilder {
    fun match(block: ShellMatcher.() -> Unit) {}
    fun respond(block: ShellMockResponse.() -> Unit) {}
    fun build() = ShellMock()
}

class ShellMatcher {
    var command: String? = null
    infix fun String.startsWith(prefix: String): Boolean = true // DSL marker
}

class ShellMockResponse {
    var stdout: String = ""
    var exitCode: Int = 0
}

data class ShellMock(val placeholder: Unit = Unit)

// Exceptions
class WorkflowDefinitionException(message: String, cause: Throwable? = null) : Exception(message, cause)
class WorkflowTimeoutException(message: String) : Exception(message)

// Implementation placeholder (actual impl in lemline-testing module)
internal class TestWorkflowExecutorImpl(
    private val broker: BrokerType,
    private val database: DatabaseType,
    private val runnerBinaryPath: String?
) : TestWorkflowExecutor {
    override val cloudEventCapture: CloudEventCapture
        get() = TODO("Implementation in lemline-testing")
    override val cloudEventDelivery: CloudEventDelivery
        get() = TODO("Implementation in lemline-testing")
    override val stateHooks: WorkflowStateHooks
        get() = TODO("Implementation in lemline-testing")

    override suspend fun start() = TODO("Implementation in lemline-testing")
    override suspend fun stop() = TODO("Implementation in lemline-testing")
    override suspend fun defineWorkflow(yaml: String) = TODO("Implementation in lemline-testing")
    override suspend fun runWorkflow(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig?,
        timeout: Duration
    ): WorkflowResult = TODO("Implementation in lemline-testing")

    override suspend fun startWorkflowAsync(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig?
    ): String = TODO("Implementation in lemline-testing")
}
