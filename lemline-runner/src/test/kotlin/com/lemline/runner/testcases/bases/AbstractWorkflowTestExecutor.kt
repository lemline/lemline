// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.impl.WorkflowDependency
import com.lemline.core.testcases.impl.WorkflowTestExecutor
import com.lemline.core.testcases.impl.WorkflowTestResult
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.activities.TestModeConfiguration
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.definitions.DefinitionModel
import com.lemline.runner.definitions.DefinitionRepository
import com.lemline.runner.listeners.ListenerRepository
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.lifecycleevents.TestLifecycleEventListener
import io.cloudevents.CloudEvent
import io.cloudevents.jackson.JsonFormat
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/**
 * Abstract base class for workflow test executors providing common execution logic.
 *
 * Implements the template method pattern where the common workflow execution flow
 * is defined here, and subclasses provide specific implementations for:
 * - [sendInitialCommand] - how to send the initial command (channel vs emitter)
 * - [awaitCompletion] - how to wait for completion (with optional message routing)
 *
 * For listen task tests with CloudEvents, subclasses should override [execute] to:
 * 1. Start the workflow
 * 2. Wait for listener registration via [waitForListenerRegistration]
 * 3. Send CloudEvents via [sendCloudEvents]
 * 4. Wait for completion
 *
 * All executors detect workflow completion via lifecycle CloudEvents captured by
 * [TestLifecycleEventListener] which subscribes to the broker, ensuring that events
 * actually flow through the messaging infrastructure.
 */
@ExperimentalTime
internal abstract class AbstractWorkflowTestExecutor : WorkflowTestExecutor {

    private val logger = logger()

    /** JSON serializer for CloudEvents */
    protected val cloudEventJsonFormat = JsonFormat()

    protected abstract val definitionRepository: DefinitionRepository
    protected abstract val databaseManager: DatabaseManager
    protected abstract val lifecycleListener: TestLifecycleEventListener
    protected abstract val starter: Starter
    protected abstract val lifecycleHook: LifecycleEventHook
    protected abstract val listenerRepository: ListenerRepository
    protected abstract val testModeConfiguration: TestModeConfiguration

    /** Default timeout for workflow completion in seconds (configurable via system property) */
    protected open val defaultTimeoutSeconds: Long by lazy {
        System.getProperty("test.workflow.timeout.seconds")?.toLongOrNull() ?: 5L
    }

    /** Timeout for listen task tests with CloudEvents (configurable via system property) */
    protected open val listenTimeoutSeconds: Long by lazy {
        System.getProperty("test.workflow.listen-timeout.seconds")?.toLongOrNull() ?: 10L
    }

    /** Delay between polling for listener registration */
    protected open val listenerPollIntervalMs: Long = 20L

    /** Maximum time to wait for listener registration */
    protected open val listenerWaitTimeoutMs: Long = 3_000L

    /** Delay between CloudEvents to ensure ordering (configurable via system property) */
    protected open val cloudEventSendIntervalMs: Long? by lazy {
        System.getProperty("test.workflow.cloudevent-send-interval-ms")?.toLongOrNull()
    }

    /**
     * Sends the initial command to start workflow execution.
     * Implementations may use in-memory channels or broker emitters.
     */
    protected abstract suspend fun sendInitialCommand(message: InstanceMessage<out WorkflowCommand>)

    /**
     * Waits for workflow completion, optionally routing messages for in-memory testing.
     * Returns the workflow result or a timeout failure.
     */
    protected abstract suspend fun awaitCompletion(
        workflowId: WorkflowId,
        timeoutSeconds: Long
    ): WorkflowTestResult

    /**
     * Sends a single CloudEvent payload string to the messaging infrastructure.
     * Implementations may use in-memory channels or broker emitters.
     *
     * @param payload The serialized CloudEvent JSON string
     */
    protected abstract suspend fun sendCloudEventPayload(payload: String)

    /**
     * Hook called after listener registration is confirmed, before sending CloudEvents.
     * Subclasses can override to add delays (e.g., for DB commit propagation).
     */
    protected open suspend fun onListenerRegistered() {
        // Default: no-op
    }

    /**
     * Hook called after all CloudEvents are sent, before waiting for completion.
     * Subclasses can override to add delays (e.g., for outbox processing).
     */
    protected open suspend fun onCloudEventsSent() {
        // Default: no-op
    }

    override suspend fun execute(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>,
        mockConfig: MockConfiguration,
        cloudEvents: List<CloudEvent>,
        validateDefinition: Boolean,
    ): WorkflowTestResult {
        val uniqueName = "workflow-${yaml.hashCode()}"
        val workflowId = WorkflowId.random()

        testModeConfiguration.setMockConfiguration(mockConfig)
        return try {
            lifecycleListener.clear()
            registerDependencies(dependencies, validateDefinition)
            registerWorkflowInDatabase(yaml, namespace, uniqueName, version, validateDefinition)

            prepareAndStartWorkflow(workflowId, namespace, uniqueName, version, input)

            if (cloudEvents.isNotEmpty()) {
                executeWithCloudEvents(workflowId, cloudEvents)
            } else {
                awaitCompletion(workflowId, timeoutSeconds = defaultTimeoutSeconds)
            }
        } catch (e: Exception) {
            WorkflowTestResult.Failure(
                error = e.message ?: e::class.simpleName ?: "Unknown error",
                exception = e
            )
        } finally {
            testModeConfiguration.clearMockConfiguration()
        }
    }

    /**
     * Prepares and starts a workflow, returning the initial message.
     */
    private suspend fun prepareAndStartWorkflow(
        workflowId: WorkflowId,
        namespace: String,
        name: String,
        version: String,
        input: JsonElement
    ): InstanceMessage<out WorkflowCommand> {
        val prepared = starter.prepareWorkflow(
            workflowId = workflowId,
            workflowNamespace = WorkflowNamespace(namespace),
            workflowName = WorkflowName(name),
            optionalVersion = WorkflowVersion(version),
            workflowInput = input,
            hasWaitingParent = false,
            zoneId = null,
            onError = { error -> throw IllegalStateException(error) }
        )

        val initialMessage = prepared.instanceMessage
            ?: throw IllegalStateException("Cannot test cron-scheduled workflows directly")

        prepared.onWorkflowCreated(lifecycleHook)
        sendInitialCommand(initialMessage)

        return initialMessage
    }

    /**
     * Executes workflow with CloudEvents for listen task tests.
     */
    private suspend fun executeWithCloudEvents(
        workflowId: WorkflowId,
        cloudEvents: List<CloudEvent>
    ): WorkflowTestResult {
        val listenerFound = waitForListenerRegistration(workflowId)
        if (!listenerFound) {
            return WorkflowTestResult.Failure(
                error = "Timeout waiting for listener registration for workflow $workflowId",
                exception = null
            )
        }

        onListenerRegistered()
        sendCloudEvents(cloudEvents)
        onCloudEventsSent()

        return awaitCompletion(workflowId, timeoutSeconds = listenTimeoutSeconds)
    }

    /**
     * Waits for a listener to be registered in the database for the given workflow.
     *
     * @param workflowId The workflow ID to check for listener registration
     * @return true if listener found, false if timeout
     */
    protected open suspend fun waitForListenerRegistration(workflowId: WorkflowId): Boolean {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < listenerWaitTimeoutMs) {
            val listener = listenerRepository.listAll().firstOrNull { it.workflowId == workflowId }
            if (listener != null) {
                logger.debug { "Listener found for workflow $workflowId after ${System.currentTimeMillis() - startTime}ms" }
                return true
            }
            delay(listenerPollIntervalMs)
        }

        logger.warn { "Timeout waiting for listener registration for workflow $workflowId" }
        return false
    }

    /**
     * Sends CloudEvents to the messaging infrastructure.
     *
     * @param events The CloudEvents to send
     */
    protected suspend fun sendCloudEvents(events: List<CloudEvent>) {
        for ((index, event) in events.withIndex()) {
            val payload = String(cloudEventJsonFormat.serialize(event), Charsets.UTF_8)
            logger.debug { "Sending CloudEvent: type=${event.type}, id=${event.id}" }
            sendCloudEventPayload(payload)

            // Small delay between events to ensure ordering
            if (index < events.lastIndex) {
                cloudEventSendIntervalMs?.let { delay(it) }
            }
        }

        logger.debug { "Sent ${events.size} CloudEvents" }
    }

    /**
     * Registers workflow dependencies in the database.
     */
    private suspend fun registerDependencies(dependencies: List<WorkflowDependency>, validateDefinition: Boolean) {
        dependencies.forEach { dep ->
            registerWorkflowInDatabase(dep.yaml, dep.namespace, dep.name, dep.version, validateDefinition)
        }
    }

    /**
     * Waits for workflow result using lifecycle events with timeout handling.
     * Common implementation used by subclasses that don't need message routing.
     */
    protected suspend fun awaitLifecycleResult(
        workflowId: WorkflowId,
        timeoutSeconds: Long
    ): WorkflowTestResult {
        return try {
            lifecycleListener.awaitWorkflowResult(workflowId, timeout = timeoutSeconds.seconds)
        } catch (e: TimeoutException) {
            WorkflowTestResult.Failure(
                error = "Workflow did not complete within $timeoutSeconds seconds. " +
                    "Captured events: ${lifecycleListener.summary()}",
                exception = e
            )
        }
    }

    /**
     * Registers a workflow definition in the database.
     */
    protected suspend fun registerWorkflowInDatabase(
        yaml: String,
        namespace: String,
        name: String,
        version: String,
        validateDefinition: Boolean
    ) {
        val ns = WorkflowNamespace(namespace)
        val n = WorkflowName(name)
        val v = WorkflowVersion(version)
        val fullYaml = addDocumentHeader(yaml, namespace, name, version)

        if (validateDefinition) {
            WorkflowCache.parseYamlAndPut(fullYaml)
        } else {
            WorkflowCache.parseYamlAndPutNoValidation(fullYaml)
        }

        databaseManager.withTransaction { conn ->
            val existing = definitionRepository.findByNameAndVersion(ns, n, v, conn)
            if (existing != null) definitionRepository.delete(existing, conn)

            definitionRepository.insert(
                DefinitionModel(namespace = ns, name = n, version = v, definition = fullYaml),
                conn
            )
        }
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
