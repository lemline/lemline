// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.states.WorkflowState
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.starters.Starter
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
@ApplicationScoped
@ExperimentalSerializationApi
internal class DatabaseMessageHandler(
    private val parentRepository: ParentRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    private val instanceEmitter: InstanceMessageEmitter,
    private val starter: Starter,
    override val metrics: DatabaseMessageSubscriberMetrics,
) : MessageHandler<DatabaseMessage> {

    override var logger = logger()

    val maxAttempts: Int = 6
    val totalBudgetMs: Long = 50_000
    val singleAttemptTimeoutMs: Long = 10_000

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: DatabaseMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    // ========================================
    // Deserialization
    // ========================================

    /**
     * Deserializes the message payload. Returns the DatabaseMessage
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun Message<String>.deserialize(): DatabaseMessage = try {
        DatabaseMessage.fromJsonString(payload)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALIZATION_FAILURE) { deserializationFailed(e) }
    }

    private suspend fun Message<String>.deserializationFailed(cause: Exception) {
        val failure = FailureModel.from(
            id = IDV7.random(),
            payload = payload,
            reason = DESERIALIZATION_FAILURE,
            error = cause
        )
        failureRepository.insert(failure)
    }

    // ========================================
    // Serialization & Emission
    // ========================================

    /**
     * DatabaseMessage does not need serialization as it doesn't chain to other messages.
     */
    override suspend fun DatabaseMessage.serialize(): String {
        error("DatabaseMessage should not be serialized - it doesn't chain to other messages")
    }

    /**
     * DatabaseMessage does not emit to other channels.
     */
    override suspend fun emit(payload: String) {
        error("DatabaseMessage should not be emitted - it doesn't chain to other messages")
    }

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles the DatabaseMessage sealed class.
     */
    @Throws(CompensationException::class)
    override suspend fun DatabaseMessage.handle(): DatabaseMessage? {
        retry(
            label = "${this::class.simpleName}",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
            singleAttemptTimeoutMs = singleAttemptTimeoutMs
        ) {
            when (this) {
                is DatabaseMessage.WorkflowPersistence -> {
                    handleWorkflowPersistence(this.instance)
                }

                is DatabaseMessage.InfrastructureFailure -> {
                    handleInfrastructureFailure(this)
                }

                is DatabaseMessage.DeserializationFailure -> {
                    handleDeserializationFailure(this)
                }
            }
        }
        return null
    }


    /**
     * Handles workflow persistence by pattern matching on WorkflowState.
     * Routes different states to appropriate outbox tables:
     * - Waiting → WaitOutbox
     * - Retrying → RetryOutbox
     * - RunningChildWorkflow → ParentOutbox + child creation
     * - Completed → Parent completion or schedule completion
     * - Failed → FailureModel
     */
    private suspend fun handleWorkflowPersistence(instance: InstanceMessage) {
        when (val state = instance.workflowState) {
            is WorkflowState.Waiting -> {
                waitRepository.insert(
                    WaitOutboxModel(
                        instanceMessage = instance,
                        outboxScheduledFor = state.waitUntil
                    )
                )
            }

            is WorkflowState.Retrying -> {
                retryRepository.insert(
                    RetryOutboxModel.from(
                        instance = instance,
                        outboxScheduledFor = state.retryAt,
                        error = IllegalStateException("Task failed and will be retried"), // TODO this is not the correct exception
                        reason = "Task retry"
                    )
                )
            }

            is WorkflowState.RunningChildWorkflow -> {
                handleRunningChildWorkflow(instance, state)
            }

            is WorkflowState.Completed -> {
                handleCompletion(instance, state)
            }

            is WorkflowState.Failed -> {
                val exception = InternalWorkflowException(state.error)
                failureRepository.insert(
                    FailureModel.from(
                        instance = instance,
                        error = exception,
                        reason = getFailureReason(exception)
                    )
                )
            }

            is WorkflowState.ReadyForNextTask,
            is WorkflowState.Starting -> {
                error("Unexpected state in database handler: $state")
            }
        }
    }

    private suspend fun handleRunningChildWorkflow(
        instance: InstanceMessage,
        state: WorkflowState.RunningChildWorkflow
    ) {
        failureRepository.withTransaction { conn ->
            // Insert parent
            val parentId = IDV7.random()
            parentRepository.insert(
                ParentOutboxModel(
                    id = parentId,
                    instanceMessage = instance,
                    outboxScheduledFor = null
                ),
                conn
            )

            // Create the child + optional schedule
            val (child, schedule) = starter.getStartingMessages(
                workflowId = WorkflowId.random(),
                workflowNamespace = state.childConfig.namespace,
                workflowName = state.childConfig.name,
                optionalVersion = state.childConfig.version,
                workflowInput = state.childConfig.input,
                parentId = parentId,
                zoneId = null
            ) { error(it) }

            // Insert schedule if present
            schedule?.let { scheduleRepository.insert(it, conn) }

            // Emit child to the workflow channel
            child?.let { instanceEmitter.send(it) }
        }
    }

    private suspend fun handleCompletion(
        instance: InstanceMessage,
        state: WorkflowState.Completed
    ) {
        // Handle parent completion
        instance.parentId?.let { parentId ->
            parentRepository.findById(parentId)?.let { parent ->
                // Validate parent state
                val currentState = parent.instanceMessage.workflowState
                if (currentState !is WorkflowState.RunningChildWorkflow) {
                    error("CRITICAL - Parent workflow ${parent.workflowId} is in unexpected state $currentState")
                }

                // Update parent with child output
                val updatedParent = parent.copy(
                    instanceMessage = parent.instanceMessage.copy(
                        workflowState = currentState.copy(rawOutput = state.output)
                    ),
                    outBoxStatus = OutBoxStatus.SENT,
                    outboxScheduledFor = Clock.System.now()
                )

                // Send parent to workflow channel
                instanceEmitter.send(updatedParent.instanceMessage)
                parentRepository.update(updatedParent)

                logger.debug {
                    "Parent workflow ${updatedParent.workflowId} resumed after child completion"
                }
            } ?: error("CRITICAL - Unable to find parent $parentId")
        }

        // Handle schedule completion
        // TODO: Determine isScheduledAfter from workflow definition
        // For now, check if workflow exists in schedule table
        scheduleRepository.findByWorkflowId(instance.workflowId)?.let { schedule ->
            schedule.scheduleAfterCompletion()
            scheduleRepository.update(schedule)
            logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.outboxScheduledFor}" }
        }
    }

    /**
     * Handles infrastructure failures (database errors, definition retrieval errors, etc.).
     * Routes based on retryable flag:
     * - retryable=true → RetryOutbox (transient errors like DB connection failures)
     * - retryable=false → FailureModel (permanent errors like missing definitions)
     */
    private suspend fun handleInfrastructureFailure(message: DatabaseMessage.InfrastructureFailure) {
        if (message.retryable) {
            // Save to retry outbox - will be retried later
            retryRepository.insert(
                RetryOutboxModel.from(
                    id = IDV7.random(),
                    instance = message.instance,
                    outboxScheduledFor = Clock.System.now(), // TODO: Calculate backoff
                    error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
                    reason = message.reason
                )
            )
        } else {
            // Save to failure table - permanent error
            failureRepository.insert(
                FailureModel.from(
                    id = IDV7.random(),
                    instance = message.instance,
                    error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
                    reason = message.reason
                )
            )
        }
    }

    /**
     * Handles message deserialization failures.
     * Saves to failure table since we cannot parse the message into an InstanceMessage.
     * Only the raw payload and error details are available.
     */
    private suspend fun handleDeserializationFailure(message: DatabaseMessage.DeserializationFailure) {
        failureRepository.insert(
            FailureModel.from(
                id = IDV7.random(),
                payload = message.payload,
                error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
                reason = DESERIALIZATION_FAILURE
            )
        )
    }
}
