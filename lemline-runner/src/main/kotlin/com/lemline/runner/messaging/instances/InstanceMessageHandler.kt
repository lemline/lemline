// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.database.createDeserializationFailure
import com.lemline.runner.messaging.database.toFailure
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.repositories.DefinitionRepository
import io.serverlessworkflow.api.types.Workflow
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * MessageHandler is responsible for consuming workflow messages from the incoming channel,
 * processing them, and sending the results to the outgoing channel. It orchestrates
 * the entire lifecycle of a message.
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
internal class InstanceMessageHandler(
    private val instanceEmitter: InstanceMessageEmitter,
    private val databaseEmitter: DatabaseMessageEmitter,
    private val definitionRepository: DefinitionRepository,
    override val metrics: InstanceMessageSubscriberMetrics,
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

        // Send deserialization failure to database channel
        throw CompensationException(DESERIALIZATION_FAILURE) {
            databaseEmitter.send(
                createDeserializationFailure(
                    payload = payload,
                    exception = e
                )
            )
        }
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

            // Send error to the database channel (not retryable - logic error).
            // We cannot serialize the next message
            throw CompensationException(SERIALIZATION_FAILURE) {
                databaseEmitter.send(
                    current.toFailure(
                        exception = e,
                        reason = SERIALIZATION_FAILURE,
                        retryable = false
                    )
                )
            }
        }
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

            // Send infrastructure failure to database channel (retryable - DB might recover)
            val reason = getFailureReason(e)
            throw CompensationException(reason) {
                databaseEmitter.send(
                    toFailure(
                        exception = e,
                        reason = reason,
                        retryable = true
                    )
                )
            }
        }

        if (workflow != null) return workflow

        // Still not found -> non-retryable infrastructure failure
        val errorMsg = "Definition not found for workflow $workflowNamespace/$workflowName/$workflowVersion"
        logger.error { "$errorMsg. Storing in failure table for manual inspection." }

        val error = IllegalStateException(errorMsg)

        throw CompensationException(DEFINITION_MISSING) {
            databaseEmitter.send(
                toFailure(
                    exception = error,
                    reason = DEFINITION_MISSING,
                    retryable = false
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
                if (parentId != null || workflow.schedule?.after != null) {
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

    private suspend fun sendToDatabase(message: InstanceMessage<WorkflowCommand>, event: WorkflowEvent) {
        databaseEmitter.send(
            DatabaseMessage.WorkflowPersistence(
                InstanceMessage(
                    workflowInfo = message.workflowInfo,
                    workflowState = event,
                    parentId = message.parentId,
                )
            )
        )
    }

    /**
     * Detects if this is a branch returning to its fork parent by checking
     * if there's an active fork in the database at this position.
     */
//    private suspend fun InstanceMessage.isForkBranchCompletion(state: WorkflowState.TaskScheduled): Boolean {
//        // Check if there's a fork at this position in the database
//        val fork = forkRepository.findByWorkflowIdAndPosition(
//            workflowInfo.workflowId,
//            state.nodePosition
//        )
//        return fork != null
//    }


}
