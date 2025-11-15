// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.orchestrator.ExecutionMode
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.states.WorkflowState
import com.lemline.runner.failures.FailureReasons.DEFINITION_MISSING
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.MESSAGE_EMISSION_FAILURE
import com.lemline.runner.failures.FailureReasons.SERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.WORKFLOW_EXECUTION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.database.CompletedMessage
import com.lemline.runner.messaging.database.DATABASE_OUT_CHANNEL
import com.lemline.runner.messaging.database.DatabaseMessageEmitter
import com.lemline.runner.messaging.database.IngestionMessage
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.repositories.DefinitionRepository
import com.lemline.runner.repositories.FAILURE_TABLE
import com.lemline.runner.repositories.RETRY_TABLE
import com.lemline.runner.starters.Starter
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
    private val starter: Starter,
    override val metrics: InstanceMessageSubscriberMetrics,
) : MessageHandler<InstanceMessage> {
    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    /**
     * Deserializes the message payload. Returns the InstanceMessage
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage = try {
        InstanceMessage.fromMessage(this)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALIZATION_FAILURE) { deserializationFailed(e) }
    }

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
    private suspend fun InstanceMessage.findWorkflowDefinition(): Workflow = try {
        // Try to get from cache first, then from repository if not found
        DefinitionCache.getWorkflow(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion
        ) ?: definitionRepository.findByNameAndVersion(
            workflowNamespace,
            workflowName,
            workflowVersion
        )
            ?.definition
            ?.let { DefinitionCache.parseAndPut(it) }
    } catch (e: Exception) {
        logger.error(e) { "Error during workflow definition retrieval." }
        emitToRetry(e, getFailureReason(e))
    } ?: run {
        val errorMsg =
            "Workflow $workflowNamespace:$workflowName:$workflowVersion not found."
        logger.error { "$errorMsg Storing the message in the $RETRY_TABLE table for manual inspection." }
        emitAsFailed(IllegalStateException(errorMsg), DEFINITION_MISSING)
    }

    /**
     * Executes one step of the workflow using WorkflowOrchestrator.
     *
     * This method calls the functional WorkflowOrchestrator to execute one activity,
     * then pattern matches on the returned WorkflowState to determine the next action.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage.executeStep(workflow: Workflow): InstanceMessage? {
        return try {
            // Execute using WorkflowOrchestrator
            val nextState = WorkflowOrchestrator.resume(
                workflow = workflow,
                state = workflowState,
                executionMode = ExecutionMode.ACTIVITY_BY_ACTIVITY
            )

            // Handle the outcome
            handleWorkflowState(nextState)
        } catch (e: Exception) {
            logger.error(e) { "Failed to execute workflow step. Storing current state in the $FAILURE_TABLE table for manual inspection." }
            emitAsFailed(e, WORKFLOW_EXECUTION_FAILURE)
        }
    }

    /**
     * Handles the different WorkflowState outcomes by pattern matching.
     *
     * @return InstanceMessage to emit for next step, or null if paused/terminal
     */
    private suspend fun InstanceMessage.handleWorkflowState(nextState: WorkflowState): InstanceMessage? {
        return when (nextState) {
            is WorkflowState.ReadyForNextTask -> {
                // Activity completed - return next message to be emitted
                logger.debug { "Activity completed at ${nextState.nodePosition}" }
                copy(workflowState = nextState)
            }

            is WorkflowState.Waiting -> {
                // Check if wait time has already been reached
                if (nextState.waitUntil <= Clock.System.now()) {
                    // Wait already completed - continue immediately
                    logger.debug { "Wait time already reached for task at ${nextState.nodePosition}, continuing immediately" }
                    copy(workflowState = nextState)
                } else {
                    // Wait time in future - create outbox and pause
                    logger.debug { "Starting wait task at ${nextState.nodePosition}, resuming at ${nextState.waitUntil}" }

                    val waitOutbox = WaitOutboxModel(
                        id = IDV7.random(),
                        instanceMessage = copy(workflowState = nextState),
                        outboxScheduledFor = nextState.waitUntil
                    )

                    databaseEmitter.send(
                        IngestionMessage(
                            instanceModels = listOf(waitOutbox),
                            instanceMessages = emptyList()
                        )
                    )
                    null // Paused - nothing to emit
                }
            }

            is WorkflowState.Retrying -> {
                // Check if retry time has already been reached
                if (nextState.retryAt <= Clock.System.now()) {
                    // Retry immediately
                    logger.debug { "Retry time reached for task at ${nextState.nodePosition}, retrying immediately" }
                    copy(workflowState = nextState)
                } else {
                    // Retry time in future - create outbox and pause
                    logger.debug { "Scheduling retry of task at ${nextState.nodePosition}, retrying at ${nextState.retryAt}" }

                    val retryOutbox = RetryOutboxModel.from(
                        id = IDV7.random(),
                        instance = copy(workflowState = nextState),
                        outboxScheduledFor = nextState.retryAt,
                        error = IllegalStateException("Task failed and will be retried"),  // TODO: Get actual exception
                        reason = "Task retry"
                    )

                    databaseEmitter.send(
                        IngestionMessage(
                            instanceModels = listOf(retryOutbox),
                            instanceMessages = emptyList()
                        )
                    )
                    null // Paused - nothing to emit
                }
            }

            is WorkflowState.RunningChildWorkflow -> {
                // Child workflow - create parent outbox and child message
                logger.debug { "Starting child workflow at ${nextState.nodePosition}" }
                handleChildWorkflow(nextState)
                null // Paused - nothing to emit
            }

            is WorkflowState.Completed -> {
                // Workflow completed
                logger.debug { "Workflow completed with output: ${nextState.output}" }
                handleWorkflowCompleted(nextState)
                null // Terminal - nothing to emit
            }

            is WorkflowState.Failed -> {
                // Workflow failed - create failure record
                logger.error { "Workflow failed at ${nextState.nodePosition}: ${nextState.error}" }

                // Create an exception from error (exception field is @Transient and always null after deserialization)
                val exception = InternalWorkflowException(nextState.error)
                val failureModel = FailureModel.from(
                    id = IDV7.random(),
                    instance = copy(workflowState = nextState),
                    error = exception,
                    reason = getFailureReason(exception)
                )

                databaseEmitter.send(
                    IngestionMessage(
                        instanceModels = listOf(failureModel),
                        instanceMessages = emptyList()
                    )
                )
                null // Terminal - nothing to emit
            }

            is WorkflowState.Starting -> {
                error("WorkflowOrchestrator should not return Starting state when resuming")
            }
        }
    }

    /**
     * Handles child workflow execution by creating a parent outbox and child instance message.
     */
    private suspend fun InstanceMessage.handleChildWorkflow(state: WorkflowState.RunningChildWorkflow) {
        // Create parent outbox (paused until child completes)
        val parentOutbox = ParentOutboxModel(
            id = IDV7.random(),
            instanceMessage = copy(workflowState = state),
            outboxScheduledFor = null  // Resumed by CompletedMessage
        )

        // Use Starter to create child message (handles schedule logic)
        val (childMessage, scheduleOutbox) = starter.getStartingMessages(
            workflowId = WorkflowId.random(),
            workflowNamespace = state.childConfig.namespace,
            workflowName = state.childConfig.name,
            optionalVersion = state.childConfig.version,
            workflowInput = state.childConfig.input,
            parentId = parentOutbox.id,
            zoneId = null
        ) { error(it) }

        // Emit parent and optional schedule to database, child to workflow channel
        val ingestionMessage = IngestionMessage(
            instanceModels = listOfNotNull(parentOutbox, scheduleOutbox),
            instanceMessages = listOfNotNull(childMessage)
        )

        databaseEmitter.send(ingestionMessage)
    }

    /**
     * Handles workflow completion by emitting a CompletedMessage if needed.
     */
    private suspend fun InstanceMessage.handleWorkflowCompleted(state: WorkflowState.Completed) {
        // Only emit CompletedMessage if workflow has parent or is scheduled
        // (Otherwise, it's a standalone workflow that just ends)

        // TODO: Determine isScheduledAfter from workflow definition
        val isScheduledAfter = false  // Placeholder - needs implementation

        if (parentId != null || isScheduledAfter) {
            val completedMessage = CompletedMessage(
                workflowInfo = workflowInfo,
                parentId = parentId,
                output = if (parentId != null) state.output else null,
                isScheduledAfter = isScheduledAfter
            )

            databaseEmitter.send(completedMessage)
        }
    }

    /**
     * Emits the next message in a workflow to the messaging system.
     *
     * This method serializes the given message into a JSON string and sends it using the emitter.
     * If the emission fails, the message is logged,
     * and the message is stored in the retry table for reprocessing.
     * The method ensures that no unhandled exceptions are propagated.
     */
    override suspend fun InstanceMessage.emit() {
        // Serialize the message
        val payload = try {
            this.toJsonString()
        } catch (e: Exception) {
            logger.error(e) { "Failed to serialize new message. Message will be stored in the $RETRY_TABLE table as failed" }
            emitAsFailed(e, SERIALIZATION_FAILURE)
        }
        // Emit the message
        try {
            instanceEmitter.send(payload)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to emit next message. Message will be stored in the $RETRY_TABLE table instead to be re-emit later" }
            emitToRetry(e, MESSAGE_EMISSION_FAILURE)
        }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = IngestionMessage(
            FailureModel.from(
                id = IDV7.random(),
                payload = payload,
                error = cause,
                reason = DESERIALIZATION_FAILURE
            )
        )
        databaseEmitter.send(failure)
        logger.debug { "Message sent to $DATABASE_OUT_CHANNEL channel: $failure" }
    }

    private suspend fun InstanceMessage.emitToRetry(cause: Exception, reason: String): Nothing {
        throw CompensationException(reason) { saveForRetry(cause, reason) }
    }

    private suspend fun InstanceMessage.emitAsFailed(cause: Exception, reason: String): Nothing {
        throw CompensationException(reason) { saveAsFailed(cause, reason) }
    }

    private suspend fun InstanceMessage.saveAsFailed(error: Exception, reason: String) {
        val failure = FailureModel.from(
            id = IDV7.random(),
            instance = this,
            error = error,
            reason = reason,
        )
        databaseEmitter.send(IngestionMessage(failure))
    }

    private suspend fun InstanceMessage.saveForRetry(cause: Exception, reason: String) {
        val retry = RetryOutboxModel.from(
            id = IDV7.random(),
            instance = this,
            outboxScheduledFor = Clock.System.now(), // <- TODO check first date
            error = cause,
            reason = reason,
        )
        databaseEmitter.send(IngestionMessage(retry))
    }
}
