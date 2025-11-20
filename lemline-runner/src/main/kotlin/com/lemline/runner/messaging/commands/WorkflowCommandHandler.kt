// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.InternalException
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.events.WorkflowEventEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.FailureRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * Handles workflow commands by executing workflow steps and emitting resulting events.
 *
 * Processes InstanceMessage<WorkflowCommand> from the workflow channel, executes one step
 * using WorkflowOrchestrator, and either:
 * - Emits next command for continued execution (TaskScheduled)
 * - Sends event to database channel for persistence (WaitStarted, RetryScheduled, etc.)
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class WorkflowCommandHandler(
    private val instanceEmitter: WorkflowCommandEmitter,
    private val databaseEmitter: WorkflowEventEmitter,
    private val definitionRepository: DefinitionRepository,
    private val failureRepository: FailureRepository,
    override val metrics: WorkflowCommandSubscriberMetrics,
) : MessageHandler<InstanceMessage<WorkflowCommand>> {
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage<WorkflowCommand>? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    // ========================================
    // Deserialization
    // ========================================

    /**
     * Deserializes the message payload. Returns the InstanceMessage
     *
     * This function is designed to throw only CompensationException with additional actions
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage<WorkflowCommand> = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }

        // Store deserialization failure directly (we don't have a valid InstanceMessage to work with)
        throw CompensationException(DESERIALIZATION_FAILURE) {
            deserializationFailed(e)
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureModel.from(
            id = IDV7.random(),
            payload = payload,
            error = cause,
            reason = DESERIALIZATION_FAILURE
        )
        failureRepository.insert(failure)
    }

    // ========================================
    // Serialization
    // ========================================

    /**
     * Serializes the InstanceMessage to a JSON string.
     * Can throw CompensationException for serialization errors (corrupted state).
     */
    override suspend fun serialize(
        current: InstanceMessage<WorkflowCommand>,
        next: InstanceMessage<WorkflowCommand>
    ): String {
        return try {
            next.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize message: $next" }

            // Send TaskFailed event to database (not retryable - serialization is a permanent error)
            throw CompensationException(SERIALIZATION_FAILURE) {
                databaseEmitter.send(
                    current.toTaskFailed(
                        exception = e,
                        reason = SERIALIZATION_FAILURE
                    )
                )
            }
        }
    }

    /**
     * Converts an InstanceMessage<WorkflowCommand> to InstanceMessage<WorkflowEvent>
     * for infrastructure failures that should be stored as permanent failures.
     */
    private fun InstanceMessage<WorkflowCommand>.toTaskFailed(
        exception: Exception,
        reason: String
    ): InstanceMessage<WorkflowEvent> {
        val error = InternalException.Error(
            type = exception::class.qualifiedName ?: "Unknown",
            status = 500,
            instance = workflowId.toString(),
            title = exception.message,
            details = exception.stackTraceToString()
        )

        return InstanceMessage(
            workflowInfo = workflowInfo,
            workflowState = WorkflowEvent.TaskFailed(
                taskStates = workflowState.taskStates,
                nodePosition = workflowState.nodePosition,
                rawInput = null,
                rawOutput = null,
                flowDirective = null,
                error = error,
                failedAt = Clock.System.now()
            ),
        )
    }

    // ========================================
    //  Emission
    // ========================================

    /**
     * Emits the serialized payload to the instance message broker.
     * MessageEmitter.send() handles retries internally.
     */
    override suspend fun emit(payload: String) {
        instanceEmitter.sendPayload(payload)
    }

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles the lifecycle of an incoming instance message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun handle(current: InstanceMessage<WorkflowCommand>): InstanceMessage<WorkflowCommand>? {
        // --- Get Workflow Definition ---
        val workflow = current.findWorkflowDefinition()
        // --- Execute step using WorkflowOrchestrator ---
        return current.executeStep(workflow)
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     *
     * If the workflow is still not found, the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage<WorkflowCommand>.findWorkflowDefinition(): Workflow {
        // Try cache first
        DefinitionCache.getWorkflow(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion
        )?.let { return it }

        // Fallback to repository, with error handling
        val workflow = try {
            definitionRepository.findByNameAndVersion(
                workflowNamespace,
                workflowName,
                workflowVersion
            )
                ?.definition
                ?.let { DefinitionCache.parseAndPut(it) }
        } catch (e: Exception) {
            logger.error(e) { "Error during workflow definition retrieval" }

            // Send TaskFailed event to database (broker will handle message-level retries)
            val reason = getFailureReason(e)
            throw CompensationException(reason) {
                databaseEmitter.send(
                    toTaskFailed(
                        exception = e,
                        reason = reason
                    )
                )
            }
        }

        if (workflow != null) return workflow

        // Still not found -> permanent failure
        val errorMsg = "Definition not found for workflow $workflowNamespace/$workflowName/$workflowVersion"
        logger.error { "$errorMsg. Storing in failure table for manual inspection." }

        val error = IllegalStateException(errorMsg)

        throw CompensationException(DEFINITION_MISSING) {
            databaseEmitter.send(
                toTaskFailed(
                    exception = error,
                    reason = DEFINITION_MISSING
                )
            )
        }
    }

    /**
     * Executes one step of the workflow using WorkflowOrchestrator.
     *
     * This method calls the functional WorkflowOrchestrator to execute one activity,
     * then pattern matches on the returned WorkflowState to determine the next action.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage<WorkflowCommand>.executeStep(workflow: Workflow): InstanceMessage<WorkflowCommand>? {

        // Execute using WorkflowOrchestrator
        val event = WorkflowOrchestrator.resume(
            workflow = workflow,
            state = workflowState,
            executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
        )

        // Handle the outcome
        return handleEvent(workflow, event)
    }

    /**
     * Handles the different WorkflowState outcomes by pattern matching.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage<WorkflowCommand>.handleEvent(
        workflow: Workflow,
        event: WorkflowEvent
    ): InstanceMessage<WorkflowCommand>? {
        return when (event) {
            is WorkflowEvent.TaskScheduled -> {
                // Activity scheduled
                logger.debug { "Activity completed at ${event.nodePosition}" }
                event.resume()
            }

            is WorkflowEvent.WaitStarted -> {
                // Check if the wait time has already been reached (optimization)
                if (event.waitUntil <= Clock.System.now()) {
                    logger.debug { "Wait time already reached, continuing immediately" }
                    event.resume()
                } else {
                    // Send to the database for persistence
                    logger.debug { "Starting wait task, resuming at ${event.waitUntil}" }
                    sendToDatabase(this, event)
                    null  // Paused
                }
            }

            is WorkflowEvent.TaskFailed -> {
                // Send to database for failure persistence
                logger.error { "Workflow failed at ${event.nodePosition}: ${event.error}" }
                sendToDatabase(this, event)
                null  // Terminal
            }

            is WorkflowEvent.RetryScheduled -> {
                // Check if the retry time has already been reached
                if (event.retryAt <= Clock.System.now()) {
                    logger.debug { "Retry time reached, retrying immediately" }
                    event.resume()
                } else {
                    // Send to the database for persistence
                    logger.debug { "Scheduling retry, retrying at ${event.retryAt}" }
                    sendToDatabase(this, event)
                    null  // Paused
                }
            }

            is WorkflowEvent.RunWorkflowStarted -> {
                // Send to the database for parent storage + child creation
                logger.debug { "Starting child workflow at ${event.nodePosition}" }
                sendToDatabase(this, event)
                when (event.childConfig.sync) {
                    // waiting for synchronous completion
                    true -> null
                    // Not waiting for sub-workflow completion
                    false -> event.resumeAsync()
                }
            }

            is WorkflowEvent.ForkStarted -> {
                // Send to the database for fork persistence + branch scheduling
                logger.debug { "Starting fork at ${event.nodePosition}" }
                sendToDatabase(this, event)
                null  // Paused - waiting for branches to complete
            }

            is WorkflowEvent.WorkflowCompleted -> {
                // Only persist if parent or scheduled workflow
                logger.debug { "Workflow completed with output: ${event.output}" }

                // Determine if this workflow has a parent or need to be scheduled after completion
                if (event.hasWaitingParent || workflow.schedule?.after != null) {
                    sendToDatabase(this, event)
                }
                null  // Terminal
            }

            is WorkflowEvent.ForkBranchCompleted -> {
                // Send to database for failure persistence
                logger.debug { "Branch completed at ${event.nodePosition}" }
                sendToDatabase(this, event)
                null  // Terminal
            }
        }?.let {
            copy(workflowState = it)
        }
    }

    /**
     * Sends a workflow event to the database channel for persistence.
     */
    private suspend fun sendToDatabase(message: InstanceMessage<WorkflowCommand>, event: WorkflowEvent) {
        databaseEmitter.send(
            InstanceMessage(
                workflowInfo = message.workflowInfo,
                workflowState = event,
            )
        )
    }
}
