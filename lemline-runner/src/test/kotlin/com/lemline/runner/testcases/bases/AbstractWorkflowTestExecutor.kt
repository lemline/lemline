// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.testcases.WorkflowDependency
import com.lemline.core.testcases.WorkflowTestExecutor
import com.lemline.core.testcases.WorkflowTestResult
import com.lemline.runner.config.DatabaseManager
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.models.DefinitionModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.starters.Starter
import com.lemline.runner.testcases.TestLifecycleEventListener
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement

/**
 * Abstract base class for workflow test executors providing common execution logic.
 *
 * Implements the template method pattern where the common workflow execution flow
 * is defined here, and subclasses provide specific implementations for:
 * - [sendInitialCommand] - how to send the initial command (channel vs emitter)
 * - [awaitCompletion] - how to wait for completion (with optional message routing)
 *
 * All executors detect workflow completion via lifecycle CloudEvents captured by
 * [TestLifecycleEventListener] which subscribes to the broker, ensuring that events
 * actually flow through the messaging infrastructure.
 */
@ExperimentalTime
@ExperimentalSerializationApi
internal abstract class AbstractWorkflowTestExecutor : WorkflowTestExecutor {

    protected abstract val definitionRepository: DefinitionRepository
    protected abstract val databaseManager: DatabaseManager
    protected abstract val lifecycleListener: TestLifecycleEventListener
    protected abstract val starter: Starter
    protected abstract val lifecycleHook: LifecycleEventHook

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

    override suspend fun execute(
        yaml: String,
        input: JsonElement,
        namespace: String,
        name: String,
        version: String,
        dependencies: List<WorkflowDependency>,
        mockConfig: MockConfiguration
    ): WorkflowTestResult {
        // Generate unique workflow name based on yaml content to avoid cache conflicts
        val uniqueName = "workflow-${yaml.hashCode()}"
        val workflowId = WorkflowId.random()

        return try {
            // Clear previous state
            lifecycleListener.clear()

            // Register dependencies and main workflow in database
            dependencies.forEach { dep ->
                registerWorkflowInDatabase(dep.yaml, dep.namespace, dep.name, dep.version)
            }
            registerWorkflowInDatabase(yaml, namespace, uniqueName, version)

            // Use Starter to prepare the workflow (validates input, creates message)
            val prepared = starter.prepareWorkflow(
                workflowId = workflowId,
                workflowNamespace = WorkflowNamespace(namespace),
                workflowName = WorkflowName(uniqueName),
                optionalVersion = WorkflowVersion(version),
                workflowInput = input,
                hasWaitingParent = false,
                zoneId = null,
                onError = { error -> throw IllegalStateException(error) }
            )

            // Starter returns null instanceMessage for cron-scheduled workflows
            val initialMessage = prepared.instanceMessage
                ?: throw IllegalStateException("Cannot test cron-scheduled workflows directly")

            // Emit workflow.created lifecycle event via the hook (uses TestLifecycleEventEmitter in tests)
            prepared.onWorkflowCreated(lifecycleHook)

            // Send starting command
            sendInitialCommand(initialMessage)

            // Wait for completion (subclass handles routing if needed)
            awaitCompletion(workflowId, timeoutSeconds = 30)
        } catch (e: Exception) {
            WorkflowTestResult.Failure(
                error = e.message ?: e::class.simpleName ?: "Unknown error",
                exception = e
            )
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
        version: String
    ) {
        val ns = WorkflowNamespace(namespace)
        val n = WorkflowName(name)
        val v = WorkflowVersion(version)
        val fullYaml = addDocumentHeader(yaml, namespace, name, version)

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
