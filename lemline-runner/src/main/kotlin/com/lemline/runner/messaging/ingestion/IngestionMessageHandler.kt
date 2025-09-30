// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.ingestion

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.PARENT_TABLE
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.SCHEDULE_TABLE
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import jakarta.enterprise.context.ApplicationScoped
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
internal class IngestionMessageHandler(
    private val parentRepository: ParentRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    private val forkRepository: ForkRepository,
    private val instanceEmitter: InstanceMessageEmitter,
    override val metrics: IngestionMessageSubscriberMetrics,
) : MessageHandler<IngestionMessage> {

    override var logger = logger()

    val maxAttempts: Int = 6
    val totalBudgetMs: Long = 50_000
    val singleAttemptTimeoutMs: Long = 10_000

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: IngestionMessage? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    /**
     * Deserializes the message payload. Returns the DatabaseMessage
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    override suspend fun Message<String>.deserialize(): IngestionMessage = try {
        IngestionMessage.fromJsonString(payload)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALIZATION_FAILURE) { deserializationFailed(e) }
    }

    /**
     * Handles the lifecycle of an incoming ingestion message.
     *
     * This function is designed to throw only DelegatedException with additional actions
     */
    @Throws(CompensationException::class)
    override suspend fun IngestionMessage.handle(): IngestionMessage? {
        retry(
            label = "${this::class.simpleName}",
            maxAttempts = maxAttempts,
            totalBudgetMs = totalBudgetMs,
            singleAttemptTimeoutMs = singleAttemptTimeoutMs
        ) {
            when (this) {
                is IngestionMessages -> this.handle()
                is WorkflowCompletedMessage -> this.handle()
            }
        }
        return null
    }

    private suspend fun WorkflowCompletedMessage.handle() {
        // Case of child workflow completion
        parentId?.let { parentId ->
            // if there is an error when retrieving the parent, the MessageConsumer will mark the message as failed
            parentRepository.findById(parentId)?.let { parent ->
                // updates the parent with the output at the current position
                parent.completeWith(output!!)
                // restarting the parent workflow
                instanceEmitter.send(parent.instanceMessage)
                // set parent status as SENT (allowing table cleaning by the outbox)
                parentRepository.update(parent)
                logger.debug {
                    "Parent workflow ${parent.workflowId} (${parent.workflowName} of workflow $workflowId (${workflowName}), set up to restart at position ${parent.workflowState.currentPosition} with output $output"
                }
            }
                ?: error("CRITICAL - Unable to find parent $parentId of workflow $workflowId ($workflowName) in $PARENT_TABLE table. The parent workflow will not be restarted.")
        }

        // Case of workflow completion with scheduled after
        if (isScheduledAfter) {
            scheduleRepository.findByWorkflowId(workflowId).firstOrNull()?.let { schedule ->
                // updates the scheduled execution instant from the after property.
                schedule.scheduleAfterCompletion()
                scheduleRepository.update(schedule)
                logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.runAt}" }
            }
                ?: error("CRITICAL - Unable to find workflow $workflowId ($workflowName) in $SCHEDULE_TABLE table. The workflow will not be restarted.")
        }
    }

    private suspend fun IngestionMessages.handle() {
        failureRepository.withTransaction { conn ->
            // First save to the database
            ingestionModels.forEach { model ->
                when (model) {
                    is ParentModel -> parentRepository.insert(model, conn)
                    is RetryModel -> retryRepository.insert(model, conn)
                    is ScheduleModel -> scheduleRepository.insert(model, conn)
                    is WaitModel -> waitRepository.insert(model, conn)
                    is FailureModel -> failureRepository.insert(model, conn)
                    is ForkModel -> forkRepository.insert(model, conn)
                }
            }
            // Then send instanceMessages to brokers
            instanceMessages.forEach { broker ->
                instanceEmitter.send(broker)
            }
        }
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

    override suspend fun IngestionMessage.emit() {
        error("This method should not be called")
    }
}
