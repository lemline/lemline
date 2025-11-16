// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.logger.logger
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.WorkflowState
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.ILLEGAL_STATE_FAILURE
import com.lemline.runner.failures.FailureReasons.MESSAGE_EMISSION_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.WORKFLOW_EXECUTION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.database.DatabaseMessage
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.database.createDeserializationFailure
import com.lemline.runner.messaging.database.toInfrastructureFailure
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
) : MessageHandler<InstanceMessage> {
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage? -> }

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
    override suspend fun Message<String>.deserialize(): InstanceMessage = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }

        // Send deserialization failure to database channel
        throw CompensationException(DESERIALIZATION_FAILURE) {
            databaseEmitter.send(
                createDeserializationFailure(
                    payload = payload,
                    error = e
                )
            )
        }
    }

    // ========================================
    // Serialization & Emission
    // ========================================

    /**
     * Serializes the InstanceMessage to a JSON string.
     * Can throw CompensationException for serialization errors (corrupted state).
     */
    override suspend fun InstanceMessage.serialize(): String {
        return try {
            this.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize message" }

            // Send infrastructure failure to database channel (not retryable - corrupted state)
            throw CompensationException(SERIALIZATION_FAILURE) {
                databaseEmitter.send(
                    toInfrastructureFailure(
                        error = e,
                        reason = SERIALIZATION_FAILURE,
                        retryable = false
                    )
                )
            }
        }
    }

    /**
     * Emits the serialized payload to the instance message broker.
     * Called with retry logic by MessageHandler, so just perform the send.
     */
    override suspend fun emit(payload: String) {
        instanceEmitter.send(payload)
    }

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles the lifecycle of an incoming instance message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun InstanceMessage.handle(): InstanceMessage? {
        // --- Get Workflow Definition ---
        val workflow = findWorkflowDefinition()
        // --- Execute step using WorkflowOrchestrator ---
        return executeStep(workflow)
    }

    /**
     * Retrieves a workflow definition based on the provided name and version.
     *
     * This method first attempts to fetch the workflow from a cache. If not found,
     * it retrieves the definition from a repository, parses it, and stores it in the cache.
     *
     * If the workflow is still not found, the message is saved for manual inspection.
     */
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow {
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
                    toInfrastructureFailure(
                        error = e,
                        reason = reason,
                        retryable = true
                    )
                )
            }
        }

        if (workflow != null) return workflow

        // Still not found -> non-retryable infrastructure failure
        val errorMsg = "Workflow $workflowNamespace:$workflowName:$workflowVersion not found"
        logger.error { "$errorMsg. Storing in failure table for manual inspection." }

        val error = IllegalStateException(errorMsg)

        throw CompensationException(DEFINITION_MISSING) {
            databaseEmitter.send(
                toInfrastructureFailure(
                    error = error,
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
    private suspend fun InstanceMessage.executeStep(workflow: Workflow): InstanceMessage? = try {
        // Execute using WorkflowOrchestrator
        val nextState = WorkflowOrchestrator.resume(
            workflow = workflow,
            state = workflowState,
            executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
        )

        // Handle the outcome
        handleWorkflowState(nextState, workflow)
    } catch (e: Exception) {
        logger.error(e) { "Failed to execute workflow step" }

        // Send infrastructure failure to database channel (not retryable - logic error)
        throw CompensationException(WORKFLOW_EXECUTION_FAILURE) {
            databaseEmitter.send(
                toInfrastructureFailure(
                    error = e,
                    reason = WORKFLOW_EXECUTION_FAILURE,
                    retryable = false
                )
            )
        }
    }

    /**
     * Handles the different WorkflowState outcomes by pattern matching.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage.handleWorkflowState(
        nextState: WorkflowState,
        workflow: Workflow
    ): InstanceMessage? {
        val nextInstanceMessage = copy(workflowState = nextState)
        return when (nextState) {
            is WorkflowState.ReadyForNextTask -> {
                // Activity completed - return next message to be emitted
                logger.debug { "Activity completed at ${nextState.nodePosition}" }
                nextInstanceMessage
            }

            is WorkflowState.Waiting -> {
                // Check if wait time has already been reached (optimization)
                if (nextState.waitUntil <= Clock.System.now()) {
                    logger.debug { "Wait time already reached, continuing immediately" }
                    nextInstanceMessage
                } else {
                    // Send to database for persistence
                    logger.debug { "Starting wait task, resuming at ${nextState.waitUntil}" }
                    sendToDatabase(nextInstanceMessage)
                    null  // Paused
                }
            }

            is WorkflowState.Retrying -> {
                // Check if retry time has already been reached (optimization)
                if (nextState.retryAt <= Clock.System.now()) {
                    logger.debug { "Retry time reached, retrying immediately" }
                    copy(workflowState = nextState)
                } else {
                    // Send to database for persistence
                    logger.debug { "Scheduling retry, retrying at ${nextState.retryAt}" }
                    sendToDatabase(nextInstanceMessage)
                    null  // Paused
                }
            }

            is WorkflowState.RunningChildWorkflow -> {
                // Send to database for parent storage + child creation
                logger.debug { "Starting child workflow at ${nextState.nodePosition}" }
                sendToDatabase(nextInstanceMessage)
                when (nextState.childConfig.sync) {
                    // waiting for synchronous completion
                    true -> null
                    // we continue the workflow execution right away
                    false -> nextInstanceMessage
                }
            }

            is WorkflowState.Completed -> {
                // Only persist if parent or scheduled workflow
                logger.debug { "Workflow completed with output: ${nextState.output}" }

                // Determine if this workflow has a parent or need to be scheduled after completion
                if (parentId != null || workflow.schedule?.after != null) {
                    sendToDatabase(nextInstanceMessage)
                }
                null  // Terminal
            }

            is WorkflowState.Failed -> {
                // Send to database for failure persistence
                logger.error { "Workflow failed at ${nextState.nodePosition}: ${nextState.error}" }
                sendToDatabase(nextInstanceMessage)
                null  // Terminal
            }

            is WorkflowState.Starting -> {
                // This shouldn't happen during resume - treat as infrastructure failure
                logger.error { "Unexpected Starting state when resuming workflow" }
                databaseEmitter.send(
                    toInfrastructureFailure(
                        error = IllegalStateException("Received Starting state during resume"),
                        reason = ILLEGAL_STATE_FAILURE,
                        retryable = false
                    )
                )
                null  // Terminal
            }
        }
    }

    private suspend fun sendToDatabase(instanceMessage: InstanceMessage) {
        databaseEmitter.send(
            DatabaseMessage.WorkflowPersistence(instanceMessage)
        )
    }
}
