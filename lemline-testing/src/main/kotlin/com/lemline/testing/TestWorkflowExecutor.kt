// SPDX-License-Identifier: BUSL-1.1
package com.lemline.testing

import com.lemline.common.logger.logger
import com.lemline.testing.infrastructure.BrokerType
import com.lemline.testing.infrastructure.DatabaseType
import com.lemline.testing.infrastructure.KafkaTestContainer
import com.lemline.testing.infrastructure.MySQLTestContainer
import com.lemline.testing.infrastructure.PostgresTestContainer
import com.lemline.testing.infrastructure.RabbitMQTestContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates end-to-end workflow test execution with native runner binary.
 *
 * This executor manages the full test lifecycle:
 * 1. Start infrastructure via Testcontainers (broker + database)
 * 2. Spawn native-compiled runner with `--mock-config` flag
 * 3. Define workflows via CLI command
 * 4. Start workflow instances via CLI command
 * 5. Capture CloudEvents from broker for verification
 * 6. Emit CloudEvents to broker for listen task testing
 * 7. Stop runner process and containers on teardown
 */
interface TestWorkflowExecutor : AutoCloseable {

    /**
     * Access to CloudEventCapture for reading events from broker.
     */
    val cloudEventCapture: CloudEventCapture

    /**
     * Access to WorkflowStateHooks for deterministic await utilities.
     */
    val stateHooks: WorkflowStateHooks

    /**
     * Start infrastructure (Testcontainers) and spawn native runner.
     */
    suspend fun start()

    /**
     * Stop runner process and infrastructure containers.
     */
    suspend fun stop()

    /**
     * Define a workflow via CLI command.
     *
     * @param yaml Complete workflow YAML including document header
     */
    suspend fun defineWorkflow(yaml: String)

    /**
     * Run a workflow and wait for completion.
     *
     * @param name Workflow name
     * @param version Workflow version
     * @param input Workflow input as JsonElement
     * @param mocks Mock configuration (optional)
     * @param timeout Maximum time to wait for completion
     * @return WorkflowResult with status and output
     */
    suspend fun runWorkflow(
        name: String,
        version: String,
        input: JsonElement = JsonObject(emptyMap()),
        mocks: MockConfig? = null,
        timeout: Duration = 30.seconds
    ): WorkflowResult

    /**
     * Start a workflow without waiting for completion.
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
        input: JsonElement = JsonObject(emptyMap()),
        mocks: MockConfig? = null
    ): String

    companion object {
        /**
         * Create a TestWorkflowExecutor instance.
         *
         * @param config Test configuration
         * @return TestWorkflowExecutor instance (not yet started)
         */
        fun create(config: TestConfiguration): TestWorkflowExecutor =
            TestWorkflowExecutorImpl(config)

        /**
         * Create with broker and database types.
         */
        fun create(
            broker: BrokerType,
            database: DatabaseType,
            runnerBinaryPath: Path? = null
        ): TestWorkflowExecutor = create(
            TestConfiguration(
                brokerType = broker,
                databaseType = database,
                runnerBinaryPath = runnerBinaryPath
            )
        )
    }
}

/**
 * Default implementation of TestWorkflowExecutor.
 */
internal class TestWorkflowExecutorImpl(
    private val config: TestConfiguration
) : TestWorkflowExecutor {

    private val logger = logger()

    // Infrastructure containers
    private var kafkaContainer: KafkaTestContainer? = null
    private var rabbitMQContainer: RabbitMQTestContainer? = null
    private var postgresContainer: PostgresTestContainer? = null
    private var mysqlContainer: MySQLTestContainer? = null

    // Runner process
    private var runnerProcess: RunnerProcess? = null
    private var runnerConfigPath: Path? = null
    private var mockConfigPath: Path? = null

    // CloudEvent infrastructure
    private var kafkaCapture: KafkaCloudEventCapture? = null
    private var rabbitMQCapture: RabbitMQCloudEventCapture? = null
    private val dispatcher = CloudEventDispatcher.getInstance()

    // Coroutine scope for background tasks
    private var scope: CoroutineScope? = null

    // Event capture scoped to this executor
    private val _cloudEventCapture = CloudEventCaptureImpl()
    override val cloudEventCapture: CloudEventCapture = _cloudEventCapture

    private val _stateHooks = WorkflowStateHooksImpl(_cloudEventCapture)
    override val stateHooks: WorkflowStateHooks = _stateHooks

    private val configGenerator = RunnerConfigGenerator()

    override suspend fun start() {
        logger.info { "Starting test infrastructure (${config.brokerType}, ${config.databaseType})..." }

        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // Start containers
        startContainers()

        // Generate runner config
        runnerConfigPath = configGenerator.generate(
            brokerType = config.brokerType,
            databaseType = config.databaseType,
            kafka = kafkaContainer,
            rabbitMQ = rabbitMQContainer,
            postgres = postgresContainer,
            mysql = mysqlContainer
        )

        // Start event capture
        startEventCapture()

        // Find and start runner
        val binaryPath = config.runnerBinaryPath ?: RunnerProcess.findBinary()
            ?: throw IllegalStateException("Runner binary not found. Build with: ./gradlew :lemline-runner:build")

        runnerProcess = RunnerProcess(
            binaryPath = binaryPath,
            configPath = runnerConfigPath!!,
            mockConfigPath = mockConfigPath
        )
        runnerProcess?.start(config.startupTimeout)

        logger.info { "Test infrastructure started" }
    }

    override suspend fun stop() {
        logger.info { "Stopping test infrastructure..." }

        // Stop runner
        runnerProcess?.stop(config.shutdownTimeout)
        runnerProcess = null

        // Stop event capture
        stopEventCapture()

        // Clean up temp files
        runnerConfigPath?.let { Files.deleteIfExists(it) }
        mockConfigPath?.let { Files.deleteIfExists(it) }

        // Stop containers
        stopContainers()

        // Cancel scope
        scope?.cancel()
        scope = null

        // Clear dispatcher registrations
        dispatcher.unregister(_cloudEventCapture)

        logger.info { "Test infrastructure stopped" }
    }

    override fun close() {
        kafkaContainer?.close()
        rabbitMQContainer?.close()
        postgresContainer?.close()
        mysqlContainer?.close()
        runnerProcess?.close()
        kafkaCapture?.close()
        rabbitMQCapture?.close()
    }

    override suspend fun defineWorkflow(yaml: String) {
        val binaryPath = config.runnerBinaryPath ?: RunnerProcess.findBinary()
            ?: throw IllegalStateException("Runner binary not found")

        // Write YAML to temp file
        val yamlFile = Files.createTempFile("workflow-", ".yaml")
        Files.writeString(yamlFile, yaml)

        try {
            // Call CLI to define workflow
            val process = ProcessBuilder(
                binaryPath.toString(),
                "definition", "create",
                "--config=${runnerConfigPath}",
                "--file=${yamlFile}"
            ).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) {
                throw WorkflowDefinitionException("Failed to define workflow: $output")
            }

            logger.debug { "Workflow defined: $output" }
        } finally {
            Files.deleteIfExists(yamlFile)
        }
    }

    override suspend fun runWorkflow(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig?,
        timeout: Duration
    ): WorkflowResult {
        val workflowId = startWorkflowAsync(name, version, input, mocks)
        return stateHooks.awaitCompletion(workflowId, timeout)
    }

    override suspend fun startWorkflowAsync(
        name: String,
        version: String,
        input: JsonElement,
        mocks: MockConfig?
    ): String {
        val workflowId = UUID.randomUUID().toString()

        // Register capture for this workflow
        dispatcher.register(workflowId, _cloudEventCapture)
        _cloudEventCapture.addToScope(workflowId)

        // Generate mock config if provided
        val mockPath = mocks?.let { configGenerator.generateMockConfig(it) }

        val binaryPath = config.runnerBinaryPath ?: RunnerProcess.findBinary()
            ?: throw IllegalStateException("Runner binary not found")

        // Build command
        val command = mutableListOf(
            binaryPath.toString(),
            "instance", "start",
            "--config=${runnerConfigPath}",
            "--name=$name",
            "--version=$version",
            "--id=$workflowId",
            "--input=${input}"
        )

        mockPath?.let { command.add("--mock-config=$it") }

        // Call CLI to start workflow
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        // Clean up mock config
        mockPath?.let { Files.deleteIfExists(it) }

        if (exitCode != 0) {
            throw WorkflowStartException("Failed to start workflow: $output")
        }

        logger.debug { "Workflow started: $workflowId" }
        return workflowId
    }

    private fun startContainers() {
        when (config.brokerType) {
            BrokerType.KAFKA -> {
                kafkaContainer = KafkaTestContainer().apply { start() }
                logger.info { "Kafka started: ${kafkaContainer?.getBootstrapServers()}" }
            }
            BrokerType.RABBITMQ -> {
                rabbitMQContainer = RabbitMQTestContainer().apply { start() }
                logger.info { "RabbitMQ started: ${rabbitMQContainer?.getAmqpUrl()}" }
            }
        }

        when (config.databaseType) {
            DatabaseType.POSTGRESQL -> {
                postgresContainer = PostgresTestContainer().apply { start() }
                logger.info { "PostgreSQL started: ${postgresContainer?.getJdbcUrl()}" }
            }
            DatabaseType.MYSQL -> {
                mysqlContainer = MySQLTestContainer().apply { start() }
                logger.info { "MySQL started: ${mysqlContainer?.getJdbcUrl()}" }
            }
        }
    }

    private fun stopContainers() {
        kafkaContainer?.close()
        kafkaContainer = null

        rabbitMQContainer?.close()
        rabbitMQContainer = null

        postgresContainer?.close()
        postgresContainer = null

        mysqlContainer?.close()
        mysqlContainer = null
    }

    private suspend fun startEventCapture() {
        val captureScope = scope ?: throw IllegalStateException("Scope not initialized")

        when (config.brokerType) {
            BrokerType.KAFKA -> {
                kafkaCapture = KafkaCloudEventCapture(
                    bootstrapServers = kafkaContainer!!.getBootstrapServers(),
                    topic = "lemline-lifecycle-events"
                )
                kafkaCapture?.start(captureScope)
            }
            BrokerType.RABBITMQ -> {
                rabbitMQCapture = RabbitMQCloudEventCapture(
                    host = rabbitMQContainer!!.getHost(),
                    port = rabbitMQContainer!!.getAmqpPort(),
                    username = rabbitMQContainer!!.getUsername(),
                    password = rabbitMQContainer!!.getPassword(),
                    queueName = "lemline-lifecycle-events"
                )
                rabbitMQCapture?.start()
            }
        }
    }

    private suspend fun stopEventCapture() {
        kafkaCapture?.stop()
        kafkaCapture = null

        rabbitMQCapture?.stop()
        rabbitMQCapture = null
    }
}

/**
 * Exception thrown when workflow definition fails.
 */
class WorkflowDefinitionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Exception thrown when workflow start fails.
 */
class WorkflowStartException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
