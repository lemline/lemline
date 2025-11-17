// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.states.BranchStatus
import com.lemline.core.states.WorkflowState
import io.serverlessworkflow.api.types.ForkTask
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkRepository
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
    private val forkRepository: ForkRepository,
    private val instanceEmitter: InstanceMessageEmitter,
    private val starter: Starter,
    override val metrics: DatabaseMessageSubscriberMetrics,
) : MessageHandler<DatabaseMessage> {

    override var logger = logger()

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
    override suspend fun serialize(current: DatabaseMessage, next: DatabaseMessage): String {
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
     * Database operations should fail fast - if they fail, the message will be NACKed
     * and redelivered by the broker.
     */
    @Throws(CompensationException::class)
    override suspend fun handle(current: DatabaseMessage): DatabaseMessage? {
        when (current) {
            is DatabaseMessage.WorkflowPersistence -> {
                handleWorkflowPersistence(current.instance)
            }

            is DatabaseMessage.InfrastructureFailure -> {
                handleInfrastructureFailure(current)
            }

            is DatabaseMessage.DeserializationFailure -> {
                handleDeserializationFailure(current)
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

            is WorkflowState.RunningFork -> {
                handleForkStarted(instance)
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
     * Handles fork started by:
     * 1. Persisting fork metadata and parent workflow state
     * 2. Creating branch models
     * 3. Emitting instance messages for each branch
     *
     * Similar pattern to handleRunningChildWorkflow but with multiple branches.
     *
     * Fork configuration (compete, branches) is derived from the workflow definition node.
     */
    private suspend fun handleForkStarted(instance: InstanceMessage) {
        val state = instance.workflowState as? WorkflowState.RunningFork
            ?: error("Expected RunningFork state, got ${instance.workflowState}")

        // Get fork node from workflow definition to extract fork configuration
        val workflowInfo = instance.workflowInfo
        val workflow = DefinitionCache.getWorkflow(
            namespace = workflowInfo.workflowNamespace,
            name = workflowInfo.workflowName,
            version = workflowInfo.workflowVersion
        ) ?: error("Workflow definition not found: ${workflowInfo.workflowNamespace}/${workflowInfo.workflowName}/${workflowInfo.workflowVersion}")

        val nodesMap = DefinitionCache.getNodesMap(workflow)
        val forkNode = nodesMap[state.nodePosition]
            ?: error("Fork node not found at ${state.nodePosition}")

        @Suppress("UNCHECKED_CAST")
        val forkTaskNode = forkNode as Node<ForkTask>
        val compete = forkTaskNode.task.fork.isCompete
        val branches = forkTaskNode.children ?: emptyList()

        // 1. Create fork metadata model
        val forkModel = ForkModel(
            instanceMessage = instance,
            forkPosition = state.nodePosition.toString(),
            compete = compete,
            branchCount = branches.size
        )

        // 2. Create branch models
        val forkBranchModels = branches.mapIndexed { index, branchNode ->
            ForkBranchModel(
                forkId = forkModel.id,
                branchIndex = index,
                branchName = branchNode.name,
                branchNodePosition = branchNode.position.toString(),
                status = BranchStatus.PENDING,
                output = null,
                error = null,
                completedAt = null,
                createdAt = Clock.System.now(),
                updatedAt = Clock.System.now()
            )
        }

        // 3. Insert fork and branches atomically (already uses transaction internally)
        forkRepository.insertForkWithBranches(forkModel, forkBranchModels)

        logger.debug {
            "Fork started for instance ${instance.workflowInfo.workflowId}, position ${state.nodePosition}, " +
                "compete=$compete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch
        branches.forEach { branchNode ->
            val branchMessage = instance.copy(
                workflowState = WorkflowState.ReadyForNextTask(
                    taskStates = state.taskStates,
                    nodePosition = branchNode.position,
                    rawInput = state.rawInput,
                    flowDirective = null
                )
            )

            logger.debug { "Scheduling branch ${branchNode.name} at ${branchNode.position}" }
            instanceEmitter.send(branchMessage)
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
