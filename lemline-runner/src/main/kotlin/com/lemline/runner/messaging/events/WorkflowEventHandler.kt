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
import com.lemline.core.nodes.NodePosition
import com.lemline.core.states.BranchStatus
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
import com.lemline.runner.models.ForkCompletionResult
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.starters.Starter
import io.serverlessworkflow.api.types.ForkTask
import jakarta.enterprise.context.ApplicationScoped
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
                error = e
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
     * - [WorkflowEvent.TaskFailed] → failures table
     * - [WorkflowEvent.RunWorkflowStarted] → parents table + child creation
     * - [WorkflowEvent.WorkflowCompleted] → parent completion
     * - [WorkflowEvent.ForkStarted] → forks table + branch creation
     * - [WorkflowEvent.ForkBranchCompleted] → branch completion
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

            is WorkflowEvent.TaskFailed -> handleTaskFailed(current as InstanceMessage<WorkflowEvent.TaskFailed>)

            is WorkflowEvent.RunWorkflowStarted -> handleRunWorkflowStarted(current as InstanceMessage<WorkflowEvent.RunWorkflowStarted>)

            is WorkflowEvent.WorkflowCompleted -> handleWorkflowCompleted(current as InstanceMessage<WorkflowEvent.WorkflowCompleted>)

            is WorkflowEvent.ForkStarted -> handleForkStarted(current as InstanceMessage<WorkflowEvent.ForkStarted>)

            is WorkflowEvent.ForkBranchCompleted -> handleForkBranchCompleted(current as InstanceMessage<WorkflowEvent.ForkBranchCompleted>)

            is WorkflowEvent.TaskScheduled -> error("Unexpected state in workflow event handler: $state")
        }
        return null
    }

    private suspend fun handleWaitStarted(instance: InstanceMessage<WorkflowEvent.WaitStarted>) {
        waitRepository.insert(
            WaitOutboxModel(
                instanceMessage = instance,
                scheduledFor = instance.workflowState.waitUntil
            )
        )
    }

    private suspend fun handleRetryScheduled(instance: InstanceMessage<WorkflowEvent.RetryScheduled>) {
        retryRepository.insert(
            RetryOutboxModel.from(
                instance = instance,
                scheduledFor = instance.workflowState.retryAt,
                error = IllegalStateException("Task failed and will be retried"), // TODO this is not the correct exception
                reason = "Task retry"
            )
        )
    }

    private suspend fun handleTaskFailed(instance: InstanceMessage<WorkflowEvent.TaskFailed>) {
        val exception = InternalException(instance.workflowState.error) // TODO check if this is the correct exception
        failureRepository.insert(
            FailureModel.from(
                instance = instance,
                error = exception,
                reason = getFailureReason(exception)
            )
        )
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
                    childId = childWorkflowId.value
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
        if (instance.workflowState.hasWaitingParent) {
            parentRepository.withTransaction { conn ->
                parentRepository.findByChildId(instance.workflowId)?.let { parent ->
                    // Parent state
                    val state = parent.instanceMessage.workflowState

                    // restart parent
                    instanceEmitter.send(
                        InstanceMessage(
                            workflowInfo = parent.instanceMessage.workflowInfo,
                            workflowState = state.resumeSync(instance.workflowState.output),
                        )
                    )

                    // mark parent as completed for cleanup (event-driven state - processed once)
                    parent.outboxCompletedAt = kotlin.time.Clock.System.now()
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
            "Fork started for instance ${instance.workflowId}, position ${state.nodePosition}, " +
                "compete=$isCompete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch
        branches.forEach { branchNode ->
            val branchMessage = InstanceMessage(
                workflowInfo = instance.workflowInfo,
                workflowState = instance.workflowState.startBranch(branchNode.position),
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
    private suspend fun handleForkBranchCompleted(instance: InstanceMessage<WorkflowEvent.ForkBranchCompleted>) {
        val state = instance.workflowState
        val forkPosition = state.nodePosition
        val branchOutput = state.output
        val branchName = state.branchName

        // Get fork by workflow ID and position
        val fork = forkRepository.findByWorkflowIdAndPosition(instance.workflowId, forkPosition)
            ?: error("Fork not found at $forkPosition for workflow ${instance.workflowId}")

        // Get fork branches to find which branch this is
        val branches = forkRepository.getBranches(fork.id)

        // Find the branch by name
        val branch = branches.firstOrNull { it.branchName == branchName }
            ?: error("Branch '$branchName' not found in fork at $forkPosition")

        logger.debug {
            "Branch ${branch.branchIndex} ($branchName) completed for fork ${fork.id} at $forkPosition, " +
                "output: ${branchOutput.toString().take(100)}"
        }

        // Record branch completion and check if fork is complete
        val completionResult = forkRepository.recordBranchCompletion(
            forkId = fork.id,
            branchIndex = branch.branchIndex,
            branchOutput = branchOutput
        )

        // Check if fork is complete
        if (completionResult.isComplete) {
            logger.debug {
                "Fork complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches, " +
                    "resuming parent workflow"
            }
            resumeForkParent(instance, completionResult, forkPosition)
        } else {
            logger.debug {
                "Fork not complete at $forkPosition: ${completionResult.completedCount}/${completionResult.branchCount} branches done"
            }
            // Waiting for more branches - nothing to do
        }
    }

    /**
     * Resume parent workflow after fork completes.
     * Assembles output from completed branches and creates resume message.
     */
    private suspend fun resumeForkParent(
        instance: InstanceMessage<WorkflowEvent.ForkBranchCompleted>,
        completionResult: ForkCompletionResult,
        forkPosition: NodePosition
    ) {
        // Assemble output based on compete mode
        val assembledOutput = if (completionResult.compete) {
            // Compete mode: return first completed branch output
            completionResult.branches
                .firstOrNull { it.status == BranchStatus.COMPLETED }
                ?.output
                ?.let { LemlineJson.decodeFromString<JsonElement>(it) }
                ?: error("No completed branch found in compete mode")
        } else {
            // Cooperative mode: return array in order
            val outputs = (0 until completionResult.branchCount).map { index ->
                val branch = completionResult.branches.find { it.branchIndex == index }
                    ?: error("Branch $index not found")

                require(branch.status == BranchStatus.COMPLETED) {
                    "Branch $index not completed: ${branch.status}"
                }

                LemlineJson.decodeFromString<JsonElement>(branch.output!!)
            }
            JsonArray(outputs)
        }

        // Create resume message
        val resumeMessage = InstanceMessage(
            workflowInfo = instance.workflowInfo,
            workflowState = WorkflowCommand.ResumeFromTask(
                taskStates = completionResult.taskStates,
                nodePosition = forkPosition,
                rawInput = assembledOutput,
                flowDirective = null
            ),
        )

        // Clean up fork state - mark as completed for cleanup
        val fork = forkRepository.findByWorkflowIdAndPosition(instance.workflowId, forkPosition)
        if (fork != null) {
            fork.outboxCompletedAt = kotlin.time.Clock.System.now()
            forkRepository.update(fork)
            logger.debug { "Fork parent resumed at $forkPosition, fork state marked for cleanup" }
        }

        // Emit resume message to workflow channel
        instanceEmitter.send(resumeMessage)
    }
}
