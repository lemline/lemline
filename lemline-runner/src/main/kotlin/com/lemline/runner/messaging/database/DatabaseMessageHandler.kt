// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.states.BranchStatus
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.messaging.instances.InstanceMessageEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkWaitingModel
import com.lemline.runner.models.ParentWaitingModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.outbox.OutBoxStatus
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkWaitingRepository
import com.lemline.runner.repositories.ParentWaitingRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.starters.Starter
import io.serverlessworkflow.api.types.ForkTask
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
    private val parentRepository: ParentWaitingRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    private val forkRepository: ForkWaitingRepository,
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
    // Serialization
    // ========================================

    /**
     * DatabaseMessage does not need serialization as it doesn't reemit.
     */
    override suspend fun serialize(current: DatabaseMessage, next: DatabaseMessage): String {
        error("DatabaseMessage should not be serialized - it doesn't chain to other messages")
    }

    // ========================================
    // Emission
    // ========================================

    /**
     * DatabaseMessage does not reemit.
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
            is DatabaseMessage.WorkflowPersistence -> handleWorkflowPersistence(current.instance)

            is DatabaseMessage.InfrastructureFailure -> handleInfrastructureFailure(current)

            is DatabaseMessage.DeserializationFailure -> handleDeserializationFailure(current)
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
    @Suppress("UNCHECKED_CAST")
    private suspend fun handleWorkflowPersistence(instance: InstanceMessage<WorkflowEvent>) {
        when (val state = instance.workflowState) {
            is WorkflowEvent.WaitStarted -> {
                waitRepository.insert(
                    WaitOutboxModel(
                        instanceMessage = instance as InstanceMessage<WorkflowEvent.WaitStarted>,
                        outboxScheduledFor = state.waitUntil
                    )
                )
            }

            is WorkflowEvent.RetryScheduled -> {
                retryRepository.insert(
                    RetryOutboxModel.from(
                        instance = instance as InstanceMessage<WorkflowEvent.RetryScheduled>,
                        outboxScheduledFor = state.retryAt,
                        error = IllegalStateException("Task failed and will be retried"), // TODO this is not the correct exception
                        reason = "Task retry"
                    )
                )
            }

            is WorkflowEvent.TaskFailed -> {
                val exception = InternalException(state.error) // TODO check if this is the correct exception
                failureRepository.insert(
                    FailureModel.from(
                        instance = instance as InstanceMessage<WorkflowEvent.TaskFailed>,
                        error = exception,
                        reason = getFailureReason(exception)
                    )
                )
            }

            is WorkflowEvent.RunWorkflowStarted -> handleRunWorkflowStarted(instance as InstanceMessage<WorkflowEvent.RunWorkflowStarted>)

            is WorkflowEvent.WorkflowCompleted -> handleWorkflowCompleted(instance as InstanceMessage<WorkflowEvent.WorkflowCompleted>)

            is WorkflowEvent.ForkStarted -> handleForkStarted(instance as InstanceMessage<WorkflowEvent.ForkStarted>)

            is WorkflowEvent.ForkBranchCompleted -> handleForkBranchCompleted(instance)

            is WorkflowEvent.TaskScheduled -> error("Unexpected state in database handler: $state")
        }
    }

    private suspend fun handleRunWorkflowStarted(
        instance: InstanceMessage<WorkflowEvent.RunWorkflowStarted>,
    ) {
        parentRepository.withTransaction { conn ->
            // Insert parent
            val parentId = IDV7.random()
            parentRepository.insert(
                entity = ParentWaitingModel(
                    id = parentId,
                    instanceMessage = instance
                ),
                connection = conn
            )

            // Create the child + optional schedule
            val (child, schedule) = starter.getStartingMessages(
                workflowId = WorkflowId.random(),
                workflowNamespace = instance.workflowState.childConfig.namespace,
                workflowName = instance.workflowState.childConfig.name,
                optionalVersion = instance.workflowState.childConfig.version,
                workflowInput = instance.workflowState.childConfig.input,
                parentId = parentId,
                zoneId = null
            ) { error(it) }

            // Insert schedule if present
            schedule?.let { scheduleRepository.insert(it, conn) }

            // Emit child to the workflow channel
            child?.let { instanceEmitter.send(it) }
        }
    }

    private suspend fun handleWorkflowCompleted(
        instance: InstanceMessage<WorkflowEvent.WorkflowCompleted>,
    ) {
        // Handle parent completion
        instance.parentId?.let { parentId ->
            parentRepository.withTransaction { conn ->
                parentRepository.findById(parentId, conn)?.let { parent ->
                    // Parent state
                    val state = parent.instanceMessage.workflowState

                    // restart parent
                    instanceEmitter.send(
                        InstanceMessage(
                            workflowInfo = parent.instanceMessage.workflowInfo,
                            workflowState = state.resumeSync(instance.workflowState.output),
                            parentId = parent.parentId,
                        )
                    )

                    // delete parent (event-driven state - processed once)
                    parentRepository.delete(parent, conn)

                    logger.debug {
                        "Parent workflow ${parent.workflowId} resumed after child completion"
                    }
                } ?: error("CRITICAL - Unable to find parent $parentId")
            }
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
    private suspend fun handleForkStarted(instance: InstanceMessage<WorkflowEvent.ForkStarted>) {
        val state = instance.workflowState

        // Get fork node from workflow definition to extract fork configuration
        val workflowInfo = instance.workflowInfo
        val workflow = DefinitionCache.getWorkflow(
            namespace = workflowInfo.workflowNamespace,
            name = workflowInfo.workflowName,
            version = workflowInfo.workflowVersion
        )
            ?: error("Workflow definition not found: ${workflowInfo.workflowNamespace}/${workflowInfo.workflowName}/${workflowInfo.workflowVersion}")

        val forkNode = workflow.getNode(state.nodePosition) as Node<ForkTask>

        @Suppress("UNCHECKED_CAST")
        val isCompete = forkNode.task.fork.isCompete
        val branches = forkNode.children ?: emptyList()

        // 1. Create fork metadata model
        val forkModel = ForkWaitingModel(
            instanceMessage = instance,
            forkPosition = state.nodePosition.toString(),
            compete = isCompete,
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
                createdAt = state.forkState.startedAt,
                updatedAt = state.forkState.startedAt
            )
        }

        // 3. Insert fork and branches atomically (already uses transaction internally)
        forkRepository.insertForkWithBranches(forkModel, forkBranchModels)

        logger.debug {
            "Fork started for instance ${instance.workflowInfo.workflowId}, position ${state.nodePosition}, " +
                "compete=$isCompete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch
        branches.forEach { branchNode ->
            val branchMessage = InstanceMessage(
                workflowInfo = instance.workflowInfo,
                workflowState = instance.workflowState.startBranch(branchNode.position),
                parentId = instance.parentId
            )

            logger.debug { "Scheduling branch ${branchNode.name} at ${branchNode.position}" }
            instanceEmitter.send(branchMessage)
        }
    }

    private suspend fun handleForkBranchCompleted(instance: InstanceMessage<WorkflowEvent>) {

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
//            retryRepository.insert(
//                RetryOutboxModel.from(
//                    id = IDV7.random(),
//                    instance = message.instance,
//                    outboxScheduledFor = Clock.System.now(), // TODO: Calculate backoff
//                    error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
//                    reason = message.reason
//                )
//            )
        } else {
            // Save to failure table - permanent error
//            failureRepository.insert(
//                FailureModel.from(
//                    id = IDV7.random(),
//                    instance = message.instance,
//                    error = RuntimeException("${message.errorClass}: ${message.errorMessage}"),
//                    reason = message.reason
//                )
//            )
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

    /**
     * Handles branch completion by:
     * 1. Finding the matching branch in the fork
     * 2. Recording branch completion in database (with pessimistic locking)
     * 3. Checking if fork is complete
     * 4. If complete, assembling output and resuming parent workflow
     * 5. If not complete, waiting for more branches
     */
//    private suspend fun InstanceMessage.handleForkBranchCompletion(
//        state: WorkflowState.TaskScheduled
//    ): InstanceMessage? {
//        val forkPosition = state.nodePosition
//        val branchOutput = state.rawInput
//
//        // Get fork by workflow ID and position
//        val fork = forkRepository.findByWorkflowIdAndPosition(workflowInfo.workflowId, forkPosition)
//            ?: throw IllegalStateException("Fork not found at $forkPosition")
//
//        // Get fork branches to find which branch this is
//        val branches = forkRepository.getBranches(fork.id)
//
//        // Find the branch that is still pending or running
//        // In a distributed system, we need to identify which branch completed based on the nodePosition
//        // Since all branches eventually return to the fork position, we need another way to identify them
//        // For now, we'll just take the first pending branch (this is a simplification)
//        val branch = branches.firstOrNull { it.status == com.lemline.core.states.BranchStatus.PENDING }
//            ?: throw IllegalStateException("No pending branch found for fork at $forkPosition")
//
//        logger.debug {
//            "Branch ${branch.branchIndex} (${branch.branchName}) completed for fork ${fork.id} at $forkPosition, " +
//                "output: ${branchOutput.toString().take(100)}"
//        }
//
//        // Record branch completion and check if fork is complete
//        val completionResult = forkRepository.recordBranchCompletion(
//            forkId = fork.id,
//            branchIndex = branch.branchIndex,
//            branchOutput = branchOutput
//        )
//
//        // Check if fork is complete
//        if (completionResult.isComplete) {
//            logger.debug {
//                "Fork complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches, " +
//                    "resuming parent workflow"
//            }
//            return resumeForkParent(completionResult, forkPosition)
//        } else {
//            logger.debug {
//                "Fork not complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches done"
//            }
//            return null  // Waiting for more branches
//        }
//    }

    /**
     * Resume parent workflow after fork completes.
     * Assembles output from completed branches and creates resume message.
     */
//    private suspend fun InstanceMessage.resumeForkParent(
//        completionResult: ForkCompletionResult,
//        forkPosition: com.lemline.core.nodes.NodePosition
//    ): InstanceMessage {
//        // Assemble output based on compete mode
//        val assembledOutput = if (completionResult.compete) {
//            // Compete mode: return first completed branch output
//            completionResult.branches
//                .firstOrNull { it.status == com.lemline.core.states.BranchStatus.COMPLETED }
//                ?.output
//                ?.let { com.lemline.common.json.LemlineJson.decodeFromString<kotlinx.serialization.json.JsonElement>(it) }
//                ?: throw IllegalStateException("No completed branch found in compete mode")
//        } else {
//            // Cooperative mode: return array in order
//            val outputs = (0 until completionResult.branchCount).map { index ->
//                val branch = completionResult.branches.find { it.branchIndex == index }
//                    ?: throw IllegalStateException("Branch $index not found")
//
//                require(branch.status == com.lemline.core.states.BranchStatus.COMPLETED) {
//                    "Branch $index not completed: ${branch.status}"
//                }
//
//                com.lemline.common.json.LemlineJson.decodeFromString<kotlinx.serialization.json.JsonElement>(
//                    branch.output!!
//                )
//            }
//            kotlinx.serialization.json.JsonArray(outputs)
//        }
//
//        // Create resume message
//        val resumeMessage = copy(
//            workflowState = WorkflowState.TaskScheduled(
//                taskStates = completionResult.taskStates,
//                nodePosition = forkPosition,
//                rawInput = assembledOutput,
//                flowDirective = null
//            )
//        )
//
//        // Clean up fork state - need to get fork ID first
//        val fork = forkRepository.findByWorkflowIdAndPosition(workflowInfo.workflowId, forkPosition)
//        if (fork != null) {
//            forkRepository.delete(fork.id)
//            logger.debug { "Fork parent resumed at $forkPosition, fork state deleted" }
//        }
//
//        return resumeMessage
//    }
}
