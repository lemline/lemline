// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.testcases.WorkflowDependency
import com.lemline.core.testcases.WorkflowTestExecutor
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.messaging.commands.WorkflowCommandHandler
import com.lemline.runner.messaging.events.WorkflowEventHandler
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message

/**
 * Workflow test executor that uses real message brokers (Kafka/RabbitMQ).
 *
 * Unlike [RunnerWorkflowTestExecutor] which uses in-memory channels and manual routing,
 * this executor relies on actual broker infrastructure with loopback configuration
 * (same topic/queue for in and out channels).
 *
 * Flow:
 * 1. Register workflow definitions in the database
 * 2. Set up callbacks on both handlers to track completion
 * 3. Send initial command via [WorkflowCommandEmitter] to the broker
 * 4. Handlers automatically consume from broker and produce back to it
 * 5. Detect workflow completion/failure from WorkflowCompleted/WorkflowFailed events
 *
 * The loopback configuration (same topic/queue for in/out) creates a natural message
 * loop through the broker, making this a true end-to-end integration test.
 */
@Singleton
@ExperimentalTime
@ExperimentalSerializationApi
internal class BrokerWorkflowTestExecutor : WorkflowTestExecutor {

    @Inject
    lateinit var definitionRepository: DefinitionRepository

    @Inject
    lateinit var commandHandler: WorkflowCommandHandler

    @Inject
    lateinit var eventHandler: WorkflowEventHandler

    @Inject
    lateinit var commandEmitter: WorkflowCommandEmitter

    // Track results per workflow ID for concurrent test support
    private val pendingResults = ConcurrentHashMap<WorkflowId, WorkflowTestResult>()

    override suspend fun execute(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>
    ): WorkflowTestResult {
        // Generate unique workflow name based on yaml content to avoid cache conflicts
        val uniqueName = "workflow-${yaml.hashCode()}"
        val mainWorkflowId = WorkflowId.random()

        // Use atomic variables for thread-safe result tracking
        var result: WorkflowTestResult? = null
        val hasError = AtomicBoolean(false)

        return try {
            // Setup callbacks on command handler for completion tracking and error handling
            commandHandler.onCompleteTest = { _: Message<String>, _: InstanceMessage<*>? -> }
            commandHandler.onFailureTest = { _: Message<String>, error: Throwable? ->
                if (error != null && !hasError.getAndSet(true)) {
                    val exception = error as? Exception ?: RuntimeException(error)
                    result = WorkflowTestResult.Failure(
                        error = error.message ?: error::class.simpleName ?: "Unknown error",
                        exception = exception
                    )
                }
            }

            // Use onEventProducedTest to capture workflow completion/failure events
            // This fires BEFORE events are sent to the events channel, avoiding DB lookup issues
            commandHandler.onEventProducedTest = { msg, event ->
                if (msg.workflowId == mainWorkflowId && result == null) {
                    when (event) {
                        is WorkflowEvent.WorkflowCompleted -> {
                            result = WorkflowTestResult.Success(event.output)
                        }

                        is WorkflowEvent.WorkflowFailed -> {
                            val errorMsg = listOfNotNull(event.error.type, event.error.title)
                                .joinToString(": ")
                            result = WorkflowTestResult.Failure(
                                error = errorMsg,
                                exception = null
                            )
                        }

                        else -> { /* Other events don't indicate completion */
                        }
                    }
                }
            }

            // Setup callbacks on event handler (for error tracking only)
            eventHandler.onCompleteTest = { _: Message<String>, _: InstanceMessage<WorkflowEvent>? -> }
            eventHandler.onFailureTest = { _: Message<String>, error: Throwable? ->
                if (error != null && !hasError.getAndSet(true)) {
                    val exception = error as? Exception ?: RuntimeException(error)
                    result = WorkflowTestResult.Failure(
                        error = error.message ?: error::class.simpleName ?: "Unknown error",
                        exception = exception
                    )
                }
            }

            // Register dependencies in database with their actual names
            dependencies.forEach { dep ->
                registerWorkflowInDatabase(dep.yaml, dep.namespace, dep.name, dep.version)
            }

            // Register main workflow in database
            registerWorkflowInDatabase(yaml, namespace, uniqueName, version)

            // Create initial command
            val workflowInfo = WorkflowInfo(
                WorkflowNamespace(namespace),
                WorkflowName(uniqueName),
                WorkflowVersion(version)
            )

            // Set hasWaitingParent = false so WorkflowCompleted events don't go to events channel
            // (the event handler would try to find parent records that don't exist)
            // Completion is tracked via onEventProducedTest callback instead
            val initialCommand = StepByStepOrchestrator.initCmd(
                workflowId = mainWorkflowId,
                workflowInput = input,
                hasWaitingParent = false,
                startedAt = Clock.System.now()
            )
            val initialMessage = InstanceMessage(
                workflowInfo = workflowInfo,
                workflowState = initialCommand
            )

            // Send initial command via the broker emitter
            commandEmitter.send(initialMessage)

            // Wait for workflow completion (broker tests have more latency than in-memory)
            waitForCompletion({ result }, timeoutSeconds = 30)
        } catch (e: Exception) {
            WorkflowTestResult.Failure(
                error = e.message ?: e::class.simpleName ?: "Unknown error",
                exception = e
            )
        } finally {
            // Reset callbacks to no-op
            commandHandler.onCompleteTest = { _, _ -> }
            commandHandler.onFailureTest = { _, _ -> }
            commandHandler.onEventProducedTest = { _, _ -> }
            eventHandler.onCompleteTest = { _, _ -> }
            eventHandler.onFailureTest = { _, _ -> }
        }
    }

    /**
     * Waits for workflow completion by polling the result.
     *
     * Unlike the in-memory executor, we don't need to route messages manually.
     * The broker handles message delivery, we just wait for the result callback.
     */
    private suspend fun waitForCompletion(
        getResult: () -> WorkflowTestResult?,
        timeoutSeconds: Long = 30
    ): WorkflowTestResult {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeoutSeconds * 1000
        var iterations = 0
        val maxIterations = 30000

        while (getResult() == null && iterations < maxIterations) {
            iterations++

            // Check timeout
            if (System.currentTimeMillis() - startTime > timeoutMillis) {
                return WorkflowTestResult.Failure(
                    error = "Workflow did not complete within $timeoutSeconds seconds",
                    exception = TimeoutException("Workflow execution timeout")
                )
            }

            // Poll with short delay (broker latency is higher than in-memory)
            delay(if (iterations < 10) 100 else 50)
        }

        return getResult() ?: WorkflowTestResult.Failure(
            error = "Workflow did not complete within $maxIterations iterations",
            exception = null
        )
    }

    private suspend fun registerWorkflowInDatabase(yaml: String, namespace: String, name: String, version: String) {
        val ns = WorkflowNamespace(namespace)
        val n = WorkflowName(name)
        val v = WorkflowVersion(version)

        val fullYaml = addDocumentHeader(yaml, namespace, name, version)

        // Store in database
        val existing = definitionRepository.findByNameAndVersion(ns, n, v)
        if (existing != null) {
            definitionRepository.delete(existing)
        }

        val model = DefinitionModel(
            namespace = ns,
            name = n,
            version = v,
            definition = fullYaml
        )
        definitionRepository.insert(model)
    }

    private fun addDocumentHeader(yaml: String, namespace: String, name: String, version: String): String {
        return if (yaml.contains("document:")) {
            yaml
        } else {
            """
            |document:
            |  dsl: '1.0.0'
            |  namespace: $namespace
            |  name: $name
            |  version: '$version'
            |$yaml
            """.trimMargin()
        }
    }
}
