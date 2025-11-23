// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.failures.FailureReasons.DESERIALIZATION_FAILURE
import com.lemline.runner.failures.FailureReasons.getFailureReason
import com.lemline.runner.messaging.CompensationException
import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.messaging.MessageHandler
import com.lemline.runner.messaging.commands.WorkflowCommandEmitter
import com.lemline.runner.messaging.toLogString
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkBranchModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.starters.Starter
import io.serverlessworkflow.api.types.ForkTask
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.eclipse.microprofile.reactive.messaging.Message
import org.jetbrains.annotations.TestOnly

/**
 * Handles workflow events by persisting them to the database and coordinating state transitions.
 *
 * Processes InstanceMessage<WorkflowEvent> from the database channel and routes different
 * event types to appropriate repositories:
 * - WaitStarted → waits table
 * - RetryScheduled → retries table
 * - TaskFailed → failures table
 * - RunWorkflowStarted → parents table + child creation
 * - WorkflowCompleted → parent completion
 * - ForkStarted → forks table + branch creation
 * - ForkBranchCompleted → branch completion
 */
@ExperimentalTime
@ApplicationScoped
@ExperimentalSerializationApi
internal class WorkflowEventHandler(
    private val definitions: Definitions,
    private val parentRepository: ParentRepository,
    private val retryRepository: RetryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val waitRepository: WaitRepository,
    private val failureRepository: FailureRepository,
    private val forkRepository: ForkRepository,
    private val instanceEmitter: WorkflowCommandEmitter,
    private val starter: Starter,
    override val metrics: WorkflowEventSubscriberMetrics,
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
        InstanceMessage.fromJsonString(payload)
    } catch (e: Exception) {
        logger.info { "Failed to deserialize message ${toLogString()} $payload: ${e.message}" }
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
     * WorkflowEventHandler does not reemit.
     */
    override suspend fun emit(payload: String) {
        error("WorkflowEventHandler should not emit - it doesn't chain to other messages")
    }

    // ========================================
    // Handling
    // ========================================

    /**
     * Handles workflow events by pattern matching on WorkflowEvent types.
     * Routes different events to appropriate repositories:
     * - [WorkflowEvent.WaitStarted] → waits table
     * - [WorkflowEvent.RetryScheduled] → retries table
     * - [WorkflowEvent.WorkflowFailed] → failures table
     * - [WorkflowEvent.RunWorkflowStarted] → parents table + child creation
     * - [WorkflowEvent.WorkflowCompleted] → parent completion
     * - [WorkflowEvent.ForkStarted] → forks table + branch creation
     * - [WorkflowEvent.BranchCompleted] → branch completion
     *
     * Database operations fail fast - if they fail, the message will be NACKed
     * and redelivered by the broker.
     */
    @Throws(CompensationException::class)
    @Suppress("UNCHECKED_CAST")
    override suspend fun handle(current: InstanceMessage<WorkflowEvent>): InstanceMessage<WorkflowEvent>? {
        when (val state = current.workflowState) {
            is WorkflowEvent.WaitStarted -> handleWaitStarted(current as InstanceMessage<WorkflowEvent.WaitStarted>)

            is WorkflowEvent.RetryScheduled -> handleRetryScheduled(current as InstanceMessage<WorkflowEvent.RetryScheduled>)

            is WorkflowEvent.RunWorkflowStarted -> handleRunWorkflowStarted(current as InstanceMessage<WorkflowEvent.RunWorkflowStarted>)

            is WorkflowEvent.ForkStarted -> handleForkStarted(current as InstanceMessage<WorkflowEvent.ForkStarted>)

            is WorkflowEvent.BranchCompleted -> handleBranchCompleted(current as InstanceMessage<WorkflowEvent.BranchCompleted>)

            is WorkflowEvent.BranchFailed -> handleBranchFailed(current as InstanceMessage<WorkflowEvent.BranchFailed>)

            is WorkflowEvent.WorkflowCompleted -> handleWorkflowCompleted(current as InstanceMessage<WorkflowEvent.WorkflowCompleted>)

            is WorkflowEvent.WorkflowFailed -> handleWorkflowFailed(current as InstanceMessage<WorkflowEvent.WorkflowFailed>)

            is WorkflowEvent.TaskScheduled -> error("Unexpected state in workflow event handler: $state")
        }
        return null
    }

    private suspend fun handleWaitStarted(instance: InstanceMessage<WorkflowEvent.WaitStarted>) {
        waitRepository.insert(
            WaitModel(
                instanceMessage = instance,
                outboxScheduledFor = instance.workflowState.waitUntil
            )
        )
    }

    private suspend fun handleRetryScheduled(instance: InstanceMessage<WorkflowEvent.RetryScheduled>) {
        retryRepository.insert(
            RetryModel.from(
                instance = instance,
                scheduledFor = instance.workflowState.retryAt,
                error = IllegalStateException("Task failed and will be retried"), // TODO this is not the correct exception
                reason = "Task retry"
            )
        )
    }

    private suspend fun handleWorkflowFailed(instance: InstanceMessage<WorkflowEvent.WorkflowFailed>) {
        failureRepository.withTransaction { conn ->
            val exception = InternalException(instance.workflowState.error)
            failureRepository.insert(
                FailureModel.from(
                    instance = instance,
                    exception = exception,
                    reason = getFailureReason(exception)
                ),
                conn
            )
            // If this workflow has a parent, resume it with the child exception, in case
            if (instance.hasWaitingParent) {
                parentRepository.findByChildId(instance.workflowId, conn)?.let { parent ->
                    // Parent state
                    val state = parent.instanceMessage.workflowState

                    // restart parent
                    instanceEmitter.send(
                        InstanceMessage(
                            workflowInfo = parent.instanceMessage.workflowInfo,
                            workflowState = state.resumeAsFailed(instance.workflowState.error),
                        )
                    )

                    // mark parent as completed for cleanup (event-driven state - processed once)
                    parent.outboxCompletedAt = Clock.System.now()
                    parentRepository.update(parent, conn)

                    logger.debug {
                        "Parent workflow $parent resumed after child ${instance.workflowId} completion"
                    }
                } ?: error("CRITICAL - Unable to find parent for child ${instance.workflowId}")
            }
        }
    }

    private suspend fun handleRunWorkflowStarted(instance: InstanceMessage<WorkflowEvent.RunWorkflowStarted>) {
        parentRepository.withTransaction { conn ->
            // Generate child workflow ID
            val childWorkflowId = WorkflowId.random()

            // Insert parent with child_id
            parentRepository.insert(
                entity = ParentModel(
                    id = IDV7.random(),
                    instanceMessage = instance,
                    childId = childWorkflowId
                ),
                connection = conn
            )

            // Create the child + optional schedule
            val (child, schedule) = starter.getStartingMessages(
                workflowId = childWorkflowId,
                workflowNamespace = instance.workflowState.childConfig.namespace,
                workflowName = instance.workflowState.childConfig.name,
                optionalVersion = instance.workflowState.childConfig.version,
                workflowInput = instance.workflowState.childConfig.input,
                hasWaitingParent = instance.workflowState.childConfig.sync, // <= true only for sync child
                zoneId = null
            ) { error(it) }

            // Insert schedule if present
            schedule?.let { scheduleRepository.insert(it, conn) }

            // Emit child to the workflow channel
            child?.let { instanceEmitter.send(it) }
        }
    }

    private suspend fun handleWorkflowCompleted(instance: InstanceMessage<WorkflowEvent.WorkflowCompleted>) {
        // If this workflow has a parent, resume it
        if (instance.hasWaitingParent) {
            parentRepository.withTransaction { conn ->
                parentRepository.findByChildId(instance.workflowId, conn)?.let { parent ->
                    // Parent state
                    val state = parent.instanceMessage.workflowState

                    // restart parent
                    instanceEmitter.send(
                        InstanceMessage(
                            workflowInfo = parent.instanceMessage.workflowInfo,
                            workflowState = state.resumeAsCompleted(instance.workflowState.output),
                        )
                    )

                    // mark parent as completed for cleanup (event-driven state - processed once)
                    parent.outboxCompletedAt = Clock.System.now()
                    parentRepository.update(parent, conn)

                    logger.debug {
                        "Parent workflow $parent resumed after child ${instance.workflowId} completion"
                    }
                } ?: error("CRITICAL - Unable to find parent for child ${instance.workflowId}")
            }
        }

        // Handle schedule completion
        val workflow = definitions.get(
            instance.workflowInfo.workflowNamespace,
            instance.workflowInfo.workflowName,
            instance.workflowInfo.workflowVersion
        )
            ?: error("CRITICAL - Unable to find definition of workflow ${instance.workflowInfo.workflowNamespace}/${instance.workflowInfo.workflowName}/${instance.workflowInfo.workflowVersion}.")
        if (workflow.schedule.after != null) {
            scheduleRepository.findByWorkflowId(instance.workflowId)?.let { schedule ->
                schedule.scheduleAfterCompletion()
                scheduleRepository.update(schedule)
                logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.outboxDelayedUntil}" }
            }
                ?: error("CRITICAL - Unable to find workflow ${instance.workflowId} in schedules table.")
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

        @Suppress("UNCHECKED_CAST")
        val forkNode = workflow.getNode(state.nodePosition) as Node<ForkTask>
        val isCompete = forkNode.task.fork.isCompete
        val branches = forkNode.children ?: emptyList()

        // 1. Create fork metadata model
        val forkModel = ForkModel(
            instanceMessage = instance,
            position = state.nodePosition.toString(),
            compete = isCompete
        )

        // 2. Create branch models
        val forkBranchModels = branches.map { branchNode ->
            ForkBranchModel(
                forkId = forkModel.id,
                name = branchNode.name,
                output = null,
                completedAt = null,
                failedAt = null,
                failureId = null
            )
        }

        // 3. Insert fork and branches atomically (already uses transaction internally)
        forkRepository.insertForkWithBranches(forkModel, forkBranchModels)

        logger.debug {
            "Fork started for instance ${instance.workflowId}, position ${state.nodePosition}, " +
                "compete=$isCompete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch
        branches.forEach { branchNode ->
            val branchMessage = InstanceMessage(
                workflowInfo = instance.workflowInfo,
                workflowState = WorkflowCommand.ResumeFromTask(
                    taskStates = instance.workflowState.taskStates,
                    nodePosition = branchNode.position,
                    rawInput = instance.workflowState.rawInput
                ),
            )

            logger.debug { "Scheduling branch ${branchNode.name} at ${branchNode.position}" }
            instanceEmitter.send(branchMessage)
        }
    }

    /**
     * Handles fork branch completion by:
     * 1. Finding the fork and identifying which branch completed
     * 2. Recording branch completion in database (with pessimistic locking)
     * 3. Checking if fork is complete
     * 4. If complete, assembling output and resuming parent workflow
     * 5. If not complete, waiting for more branches
     */
    private suspend fun handleBranchCompleted(instance: InstanceMessage<WorkflowEvent.BranchCompleted>) {
        val state = instance.workflowState
        val forkPosition = state.nodePosition
        val branchOutput = state.output
        val branchName = state.branchName

        forkRepository.withTransaction { conn ->
            // Get fork with branches by workflow ID and position (single query)
            val (fork, branches) = forkRepository.findByWorkflowIdAndPositionWithBranches(
                instance.workflowId,
                forkPosition,
                conn
            ) ?: error("Fork not found at $forkPosition for workflow ${instance.workflowId}")

            logger.debug {
                "Branch '$branchName' completed for fork ${fork.id} at $forkPosition, " +
                    "output: ${branchOutput.toString().take(100)}"
            }

            // Find the branch by name
            val branch = branches.firstOrNull { it.name == branchName }
                ?: error("Branch '$branchName' not found in fork at $forkPosition")

            if (branch.completedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already completed" }
                return@withTransaction null
            }

            // Update branch with completion data
            branch.output = LemlineJson.encodeToString(branchOutput)
            branch.completedAt = Clock.System.now()
            // clean failure if needed
            branch.failedAt = null
            branch.failureId?.let {
                failureRepository.deleteById(it, conn)
                branch.failureId = null
            }

            // Save the updated branch in the transaction
            forkRepository.updateBranch(branch, conn)

            // Apply business logic: check if the fork is complete based on the compete mode
            if (fork.outboxCompletedAt == null) {
                val completedCount = branches.count { it.completedAt != null }
                val outputJson = when {
                    fork.compete && completedCount == 1 -> branchOutput
                    !fork.compete && completedCount == branches.size -> {
                        val outputs = branches.map { b ->
                            val out = b.output ?: error("Branch '${b.name}' has no output")
                            LemlineJson.decodeFromString<JsonElement>(out)
                        }
                        JsonArray(outputs)
                    }

                    else -> null
                }

                // Is the fork now completed?
                if (outputJson != null) {
                    logger.debug { "Fork completed at $forkPosition with output $outputJson, resuming parent workflow" }
                    // Update fork with completion data
                    fork.output = LemlineJson.encodeToString(outputJson)
                    fork.outboxCompletedAt = Clock.System.now()
                    // Clean failure if needed
                    fork.failedAt = null
                    fork.failureId?.let {
                        failureRepository.deleteById(it, conn)
                        fork.failureId = null
                    }
                    forkRepository.update(fork, conn)

                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithCompletedTask(
                            taskStates = instance.workflowState.taskStates,
                            nodePosition = forkPosition,
                            rawOutput = outputJson,
                        ),
                    )

                    // Emit resume message to workflow channel
                    instanceEmitter.send(resumeMessage)
                } else {
                    logger.debug { "Fork not yet completed at $forkPosition" }
                    // Waiting for more branches - nothing to do
                }
            }
        }
    }

    /**
     * Handles fork branch failure by:
     * 1. Finding the fork and identifying which branch failed
     * 2. Recording branch failure in database (with pessimistic locking)
     * 3. Applying compete/cooperative error semantics:
     *    - compete=true: Wait for all branches to finish, only fail if all failed
     *    - compete=false: Fail fork immediately on first branch failure
     * 4. If fork should fail, resuming parent workflow with error
     *
     * Similar pattern to handleForkBranchCompleted but with error handling logic.
     */
    private suspend fun handleBranchFailed(instance: InstanceMessage<WorkflowEvent.BranchFailed>) {
        val state = instance.workflowState
        val forkPosition = state.nodePosition
        val branchError = state.error
        val branchName = state.branchName

        forkRepository.withTransaction { conn ->
            // Get fork with branches by workflow ID and position (single query)
            val (fork, branches) = forkRepository.findByWorkflowIdAndPositionWithBranches(
                instance.workflowId,
                forkPosition,
                conn
            ) ?: error("Fork not found at $forkPosition for workflow ${instance.workflowId}")

            logger.debug {
                "Branch '$branchName' failed for fork ${fork.id} at $forkPosition, " +
                    "error: ${branchError.title ?: branchError.type}"
            }

            // Find the branch by name
            val branch = branches.firstOrNull { it.name == branchName }
                ?: error("Branch '$branchName' not found in fork at $forkPosition")

            if (branch.failedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already failed" }
                return@withTransaction null
            }
            if (branch.completedAt != null) {
                logger.info { "Weird, the branch '$branchName' at $forkPosition is already completed" }
                return@withTransaction null
            }

            // Update branch with failure data
            branch.failedAt = Clock.System.now()
            branch.failureId =
                null // TODO Store the error here - not in table failures, as do not know yet if this failure triggers a workflow failure

            // Save the updated branch in the transaction
            forkRepository.updateBranch(branch, conn)

            // Apply business logic based on compete mode
            if (fork.outboxCompletedAt == null && fork.failedAt == null) {

                val failedCount = branches.count { it.failedAt != null }
                val error = when {
                    fork.compete && failedCount == branches.size -> instance.workflowState.error
                    !fork.compete && failedCount == 1 -> instance.workflowState.error
                    else -> null
                }

                if (error != null) {
                    logger.debug { "Fork failed at $forkPosition, resuming workflow with error" }

                    // we do not know yet if the workflow will be failing after this fork failure
                    // this exception can be caught above the fork, that's why we restart from there
                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithFailedTask(
                            taskStates = instance.workflowState.taskStates,
                            nodePosition = forkPosition,
                            error = error,
                        ),
                    )
                    instanceEmitter.send(resumeMessage)

                }
            }
        }
    }
}
