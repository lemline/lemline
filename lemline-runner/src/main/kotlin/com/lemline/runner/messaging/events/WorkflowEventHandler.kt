// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.core.errors.InternalException
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.failures.FailureModel
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.failures.FailureRepository
import com.lemline.runner.forks.ForkService
import com.lemline.runner.listeners.ListenerEventService
import com.lemline.runner.listeners.ListenerService
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.parents.ParentService
import com.lemline.runner.retries.RetryService
import com.lemline.runner.schedules.ScheduleService
import com.lemline.runner.waits.WaitService
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * Handles workflow events by persisting them to the database and coordinating state transitions.
 *
 * Processes InstanceMessage<WorkflowEvent> from the database channel and routes different
 * event types to appropriate services:
 * - WaitStarted → WaitService
 * - RetryScheduled → RetryService
 * - TaskFailed → FailureRepository
 * - RunWorkflowStarted → ParentService
 * - WorkflowCompleted → ParentService (parent resume)
 * - ForkStarted → ForkService
 * - ForkBranchCompleted → ForkService
 * - ForkBranchFailed → ForkService
 * - ListenStarted → ListenerService
 * - ListenForEachCompleted → ListenerEventService
 */
@ExperimentalTime
@ApplicationScoped
@ExperimentalSerializationApi
internal class WorkflowEventHandler(
    private val definitions: Definitions,
    private val failureRepository: FailureRepository,
    private val databaseConfig: DatabaseConfig,
    override val metrics: WorkflowEventSubscriberMetrics,
    // Feature services
    private val waitService: WaitService,
    private val retryService: RetryService,
    private val forkService: ForkService,
    private val parentService: ParentService,
    private val listenerService: ListenerService,
    private val listenerEventService: ListenerEventService,
    private val scheduleService: ScheduleService,
) : MessageHandler<InstanceMessage<WorkflowEvent>> {

    override var logger = logger()

    @TestOnly
    override var onCompleteTest = { _: Message<String>, _: InstanceMessage<WorkflowEvent>? -> }

    @TestOnly
    override var onFailureTest = { _: Message<String>, _: Throwable? -> }

    // ========================================
    // Deserialization
    // ========================================

    /**
     * Deserializes the message payload into an InstanceMessage<WorkflowEvent>.
     *
     * This function is designed to throw only CompensationException with additional actions.
     * If deserialization fails, it stores the failure and throws to NACK the message.
     */
    override suspend fun Message<String>.deserialize(): InstanceMessage<WorkflowEvent> = try {
        InstanceMessage.fromTransportPayload(payload)
    } catch (e: Exception) {
        logger.warn { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
        throw CompensationException(DESERIALIZATION_FAILURE) {
            val failure = FailureModel.from(
                payload = payload,
                reason = DESERIALIZATION_FAILURE,
                exception = e
            )
            failureRepository.insert(failure)
        }
    }

    // ========================================
    // Serialization
    // ========================================

    /**
     * WorkflowEventHandler does not need serialization as it doesn't reemit.
     */
    override suspend fun serialize(
        current: InstanceMessage<WorkflowEvent>,
        next: InstanceMessage<WorkflowEvent>
    ): String {
        error("WorkflowEventHandler should not serialize - it doesn't chain to other messages")
    }

    // ========================================
    // Emission
    // ========================================

    /**
     * WorkflowEventHandler does not reemit via the standard pipeline.
     * Message emissions are handled directly in handlers with explicit idempotent keys.
     */
    override suspend fun emit(payload: String, idempotentKey: IDV7) {
        error("WorkflowEventHandler should not emit - it doesn't chain to other messages")
    }

    /**
     * Derives an idempotent key for event messages.
     * Note: This is not used in the standard pipeline since WorkflowEventHandler
     * handles emissions directly with explicit idempotent keys.
     */
    override fun deriveIdempotentKey(next: InstanceMessage<WorkflowEvent>): IDV7 =
        next.workflowState.nodeStack.deriveIdempotentId("-event")

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles workflow events by pattern matching on WorkflowEvent types.
     * Routes different events to appropriate services.
     *
     * Database operations fail fast - if they fail, the message will be NACKed
     * and redelivered by the broker.
     */
    @Throws(CompensationException::class)
    @Suppress("UNCHECKED_CAST")
    override suspend fun handle(current: InstanceMessage<WorkflowEvent>): InstanceMessage<WorkflowEvent>? {
        when (val state = current.workflowState) {
            is WorkflowEvent.WaitStarted ->
                waitService.handleWaitStarted(current as InstanceMessage<WorkflowEvent.WaitStarted>)

            is WorkflowEvent.TaskRetryScheduled ->
                retryService.handleRetryScheduled(current as InstanceMessage<WorkflowEvent.TaskRetryScheduled>)

            is WorkflowEvent.RunWorkflowStarted ->
                parentService.handleRunWorkflowStarted(current as InstanceMessage<WorkflowEvent.RunWorkflowStarted>)

            is WorkflowEvent.ForkStarted ->
                forkService.handleForkStarted(current as InstanceMessage<WorkflowEvent.ForkStarted>)

            is WorkflowEvent.ListenStarted ->
                listenerService.handleListenStarted(current as InstanceMessage<WorkflowEvent.ListenStarted>)

            is WorkflowEvent.ForkBranchCompleted ->
                forkService.handleBranchCompleted(current as InstanceMessage<WorkflowEvent.ForkBranchCompleted>)

            is WorkflowEvent.ForkBranchFailed ->
                forkService.handleBranchFailed(current as InstanceMessage<WorkflowEvent.ForkBranchFailed>)

            is WorkflowEvent.WorkflowCompleted ->
                handleWorkflowCompleted(current as InstanceMessage<WorkflowEvent.WorkflowCompleted>)

            is WorkflowEvent.WorkflowFailed ->
                handleWorkflowFailed(current as InstanceMessage<WorkflowEvent.WorkflowFailed>)

            is WorkflowEvent.ForEachCompleted ->
                listenerEventService.handleForEachCompleted(current as InstanceMessage<WorkflowEvent.ForEachCompleted>)

            is WorkflowEvent.TaskScheduled ->
                error("Unexpected state in workflow event handler: $state")

            is WorkflowEvent.ActivityStarted ->
                error("ActivityStarted should not be sent to events channel - activities are executed inline: $state")

            is WorkflowEvent.EmitStarted ->
                error("EmitStarted should not be sent to events channel - emit is executed inline: $state")
        }
        return null
    }

    /**
     * Handles workflow failure by:
     * 1. Persisting failure to database
     * 2. Resuming parent workflow if this is a child workflow
     */
    private suspend fun handleWorkflowFailed(instance: InstanceMessage<WorkflowEvent.WorkflowFailed>) {
        val failureId = instance.workflowState.nodeStack.deriveIdempotentId("-failure")
        databaseConfig.withTransaction { conn ->
            val exception = InternalException(instance.workflowState.error)
            val rowsInserted = failureRepository.insert(
                FailureModel.from(
                    id = failureId,
                    instance = instance,
                    exception = exception,
                    reason = getFailureReason(exception)
                ),
                conn
            )
            when (rowsInserted == 0) {
                true -> {
                    logger.info { "Failure $failureId already exists (idempotent insert), skipping" }
                    return@withTransaction
                }

                false -> logger.warn { "Failure $failureId at position ${instance.workflowState.nodePosition}: ${instance.workflowState.error}" }
            }
            // If this workflow has a parent, resume it with the child exception
            if (instance.hasWaitingParent) {
                parentService.resumeParentOnChildFailure(
                    childWorkflowId = instance.workflowId,
                    childError = instance.workflowState.error,
                    connection = conn
                )
            }
        }
    }

    /**
     * Handles workflow completion by:
     * 1. Resuming parent workflow if this is a child workflow
     * 2. Scheduling next run if workflow has after schedule
     */
    private suspend fun handleWorkflowCompleted(instance: InstanceMessage<WorkflowEvent.WorkflowCompleted>) {
        // Get workflow definition for schedule check (read-only, outside transaction)
        val workflow = definitions.get(
            instance.workflowInfo.namespace,
            instance.workflowInfo.name,
            instance.workflowInfo.version
        )
            ?: error("CRITICAL - Unable to find definition of workflow ${instance.workflowInfo.namespace}/${instance.workflowInfo.name}/${instance.workflowInfo.version}.")

        val hasScheduleAfter = workflow.schedule?.after != null

        // If no parent and no schedule, nothing to do
        if (!instance.hasWaitingParent && !hasScheduleAfter) {
            return
        }

        // Single transaction for all operations
        databaseConfig.withTransaction { conn ->
            // Handle parent resume if needed
            if (instance.hasWaitingParent) {
                parentService.resumeParentOnChildCompletion(
                    childWorkflowId = instance.workflowId,
                    childOutput = instance.workflowState.output,
                    connection = conn
                )
            }

            // Handle schedule completion if needed
            if (hasScheduleAfter) {
                scheduleService.scheduleAfterCompletion(instance.workflowId, conn)
            }
        }
    }
}
