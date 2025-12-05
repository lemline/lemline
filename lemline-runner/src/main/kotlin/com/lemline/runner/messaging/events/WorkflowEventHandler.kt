// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.events

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.expressions.JQExpression
import com.lemline.core.nodes.Node
import com.lemline.core.processors.ListenConfig
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
import com.lemline.runner.models.ListenerModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.WaitModel
import com.lemline.runner.repositories.FailureRepository
import com.lemline.runner.repositories.ForkRepository
import com.lemline.runner.repositories.ListenerRepository
import com.lemline.runner.repositories.ParentRepository
import com.lemline.runner.repositories.RetryRepository
import com.lemline.runner.repositories.ScheduleRepository
import com.lemline.runner.repositories.WaitRepository
import com.lemline.runner.starters.Starter
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import jakarta.enterprise.context.ApplicationScoped
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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
 * - ListenStarted → listeners table (for CloudEvent consumption)
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
    private val listenerRepository: ListenerRepository,
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
     * Routes different events to appropriate repositories:
     * - [WorkflowEvent.WaitStarted] → waits table
     * - [WorkflowEvent.TaskRetryScheduled] → retries table
     * - [WorkflowEvent.WorkflowFailed] → failures table
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

            is WorkflowEvent.TaskRetryScheduled -> handleRetryScheduled(current as InstanceMessage<WorkflowEvent.TaskRetryScheduled>)

            is WorkflowEvent.RunWorkflowStarted -> handleRunWorkflowStarted(current as InstanceMessage<WorkflowEvent.RunWorkflowStarted>)

            is WorkflowEvent.ForkStarted -> handleForkStarted(current as InstanceMessage<WorkflowEvent.ForkStarted>)

            is WorkflowEvent.ListenStarted -> handleListenStarted(current as InstanceMessage<WorkflowEvent.ListenStarted>)

            is WorkflowEvent.ForkBranchCompleted -> handleBranchCompleted(current as InstanceMessage<WorkflowEvent.ForkBranchCompleted>)

            is WorkflowEvent.ForkBranchFailed -> handleBranchFailed(current as InstanceMessage<WorkflowEvent.ForkBranchFailed>)

            is WorkflowEvent.WorkflowCompleted -> handleWorkflowCompleted(current as InstanceMessage<WorkflowEvent.WorkflowCompleted>)

            is WorkflowEvent.WorkflowFailed -> handleWorkflowFailed(current as InstanceMessage<WorkflowEvent.WorkflowFailed>)

            is WorkflowEvent.TaskScheduled -> error("Unexpected state in workflow event handler: $state")

            is WorkflowEvent.ActivityStarted -> error("ActivityStarted should not be sent to events channel - activities are executed inline: $state")
        }
        return null
    }

    private suspend fun handleWaitStarted(instance: InstanceMessage<WorkflowEvent.WaitStarted>) {
        val waitId = instance.workflowState.nodeStack.deriveIdempotentId("-wait")
        val rowsInserted = waitRepository.insert(
            WaitModel(
                id = waitId,
                instanceMessage = instance,
                outboxScheduledFor = instance.workflowState.config.waitUntil
            )
        )
        if (rowsInserted == 0) {
            logger.info { "Wait model $waitId already exists (idempotent insert)" }
        }
    }

    private suspend fun handleRetryScheduled(instance: InstanceMessage<WorkflowEvent.TaskRetryScheduled>) {
        val retryId = instance.workflowState.nodeStack.deriveIdempotentId("-retry")
        val rowsInserted = retryRepository.insert(
            RetryModel.from(
                id = retryId,
                instance = instance,
                scheduledFor = instance.workflowState.retryAt,
                error = IllegalStateException("Task failed and will be retried"), // TODO this is not the correct exception
                reason = "Task retry"
            )
        )
        if (rowsInserted == 0) {
            logger.info { "Retry model $retryId already exists (idempotent insert)" }
        }
    }

    /**
     * Handles ListenStarted events by creating a listener row in the database.
     *
     * The listener stores all data needed for CloudEvent matching:
     * - Workflow identity (namespace, name, version) for locating the listen task in cached workflow definition
     * - Workflow position for locating the listen task in the workflow tree
     * - Workflow instance identity (for resuming when events match)
     * - Progress tracking (for ALL strategy and accumulation mode)
     *
     * Listen task configuration (strategy, filters, readAs) is retrieved from the
     * cached workflow definition using (workflowNamespace, workflowName, workflowVersion, workflowPosition).
     *
     * CloudEvents are processed by a separate handler that queries listeners
     * by workflow identity, position, and correlation values.
     */
    private suspend fun handleListenStarted(instance: InstanceMessage<WorkflowEvent.ListenStarted>) {
        val state = instance.workflowState
        val config = state.config
        val listenerId = state.nodeStack.deriveIdempotentId("-listen")

        // Create listener model - workflow identity derived from instanceMessage
        val listener = ListenerModel(
            id = listenerId,
            instanceMessage = instance,
            timeoutAt = config.timeoutAt,
            outboxScheduledFor = Clock.System.now(),
        )

        // Calculate correlation values from expect expressions
        listener.correlationValues = calculateCorrelationValues(config)

        // Insert listener into database
        val rowsInserted = listenerRepository.insert(listener)
        if (rowsInserted == 0) {
            logger.info { "Listener $listenerId already exists (idempotent insert)" }
        } else {
            logger.debug {
                "Listen task started: $listenerId for workflow ${instance.workflowId} " +
                    "at position ${state.nodePosition}"
            }
        }
    }

    private suspend fun handleWorkflowFailed(instance: InstanceMessage<WorkflowEvent.WorkflowFailed>) {
        val failureId = instance.workflowState.nodeStack.deriveIdempotentId("-failure")
        failureRepository.withTransaction { conn ->
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
            if (rowsInserted == 0) {
                logger.info { "Failure model $failureId already exists (idempotent insert), skipping" }
                return@withTransaction
            }
            // If this workflow has a parent, resume it with the child exception, in case
            if (instance.hasWaitingParent) {
                parentRepository.findByChildId(instance.workflowId, conn)?.let { parent ->
                    // Check if already processed (defense in depth - failure insert check above should catch this)
                    if (parent.outboxCompletedAt != null) {
                        logger.info { "Parent ${parent.id} already resumed for child ${instance.workflowId} (idempotent), skipping" }
                        return@withTransaction
                    }

                    // Parent state
                    val state = parent.instanceMessage.workflowState

                    // Derive resume message ID from parent model ID
                    val resumeMessageId = parent.id.derive("-resume-failed")

                    // restart parent
                    instanceEmitter.send(
                        InstanceMessage(
                            workflowInfo = parent.instanceMessage.workflowInfo,
                            workflowState = state.resumeAsFailed(instance.workflowState.error),
                        ),
                        resumeMessageId
                    )

                    // mark parent as completed for cleanup (event-driven state - processed once)
                    parent.outboxCompletedAt = Clock.System.now()
                    parentRepository.update(parent, conn)

                    logger.debug {
                        "Parent workflow $parent resumed after child ${instance.workflowId} failure"
                    }
                } ?: error("CRITICAL - Unable to find parent for child ${instance.workflowId}")
            }
        }
    }

    private suspend fun handleRunWorkflowStarted(instance: InstanceMessage<WorkflowEvent.RunWorkflowStarted>) {
        // Derive parent model ID from position + step
        val parentId = instance.workflowState.nodeStack.deriveIdempotentId("-parent")

        parentRepository.withTransaction { conn ->
            // Generate child workflow ID (deterministic from parent ID)
            val childWorkflowId = WorkflowId(parentId.derive("-child"))

            // Insert parent with child_id
            val rowsInserted = parentRepository.insert(
                entity = ParentModel(
                    id = parentId,
                    instanceMessage = instance,
                    childId = childWorkflowId
                ),
                connection = conn
            )
            if (rowsInserted == 0) {
                logger.info { "Parent model $parentId already exists (idempotent insert), skipping" }
                return@withTransaction
            }

            // Create the child + optional schedule
            val (child, schedule) = starter.getStartingMessages(
                workflowId = childWorkflowId,
                workflowNamespace = instance.workflowState.config.namespace,
                workflowName = instance.workflowState.config.name,
                optionalVersion = instance.workflowState.config.version,
                workflowInput = instance.workflowState.config.input,
                hasWaitingParent = instance.workflowState.config.sync, // <= true only for sync child
                zoneId = null
            ) { error(it) }

            // Insert schedule if present
            schedule?.let { scheduleRepository.insert(it, conn) }

            // Emit child to the workflow channel with idempotent message ID
            child?.let {
                val childMessageId = parentId.derive("-child-init")
                instanceEmitter.send(it, childMessageId)
            }
        }
    }

    private suspend fun handleWorkflowCompleted(instance: InstanceMessage<WorkflowEvent.WorkflowCompleted>) {
        // Get workflow definition for schedule check (read-only, outside transaction)
        val workflow = definitions.get(
            instance.workflowInfo.workflowNamespace,
            instance.workflowInfo.workflowName,
            instance.workflowInfo.workflowVersion
        )
            ?: error("CRITICAL - Unable to find definition of workflow ${instance.workflowInfo.workflowNamespace}/${instance.workflowInfo.workflowName}/${instance.workflowInfo.workflowVersion}.")

        val hasScheduleAfter = workflow.schedule?.after != null

        // If no parent and no schedule, nothing to do
        if (!instance.hasWaitingParent && !hasScheduleAfter) {
            return
        }

        // Single transaction for all operations
        parentRepository.withTransaction { conn ->
            // Handle parent resume if needed
            if (instance.hasWaitingParent) {
                parentRepository.findByChildId(instance.workflowId, conn)?.let { parent ->
                    // Check if already processed (idempotent handling)
                    if (parent.outboxCompletedAt != null) {
                        logger.info { "Parent ${parent.id} already resumed for child ${instance.workflowId} (idempotent), skipping" }
                        // Continue to schedule check - don't return
                    } else {
                        // Parent state
                        val state = parent.instanceMessage.workflowState

                        // Derive resume message ID from parent model ID
                        val resumeMessageId = parent.id.derive("-resume-completed")

                        // restart parent
                        instanceEmitter.send(
                            InstanceMessage(
                                workflowInfo = parent.instanceMessage.workflowInfo,
                                workflowState = state.resumeAsCompleted(instance.workflowState.output),
                            ),
                            resumeMessageId
                        )

                        // mark parent as completed for cleanup (event-driven state - processed once)
                        parent.outboxCompletedAt = Clock.System.now()
                        parentRepository.update(parent, conn)

                        logger.debug {
                            "Parent workflow $parent resumed after child ${instance.workflowId} completion"
                        }
                    }
                } ?: error("CRITICAL - Unable to find parent for child ${instance.workflowId}")
            }

            // Handle schedule completion if needed
            if (hasScheduleAfter) {
                scheduleRepository.findByWorkflowId(instance.workflowId, conn)?.let { schedule ->
                    schedule.scheduleAfterCompletion()
                    scheduleRepository.update(schedule, conn)
                    logger.debug { "Scheduled workflow ${schedule.workflowName} for ${schedule.outboxDelayedUntil}" }
                } ?: error("CRITICAL - Unable to find workflow ${instance.workflowId} in schedules table.")
            }
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

        // Derive fork model ID from position + step
        val forkId = instance.workflowState.nodeStack.deriveIdempotentId("-fork")

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

        // 1. Create fork metadata model with deterministic ID
        val forkModel = ForkModel(
            id = forkId,
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
                failedAt = null
            )
        }

        // 3. Insert fork and branches atomically (already uses transaction internally)
        val rowsInserted = forkRepository.insertForkWithBranches(forkModel, forkBranchModels)
        if (rowsInserted == 0) {
            logger.info { "Fork model $forkId already exists (idempotent insert), skipping" }
            return
        }

        logger.debug {
            "Fork started for instance ${instance.workflowId}, position ${state.nodePosition}, " +
                "compete=$isCompete, branches=${branches.size}"
        }

        // 4. Emit instance messages for each branch with idempotent message IDs
        // Branch position contains branch name, making IDs unique across branches
        branches.forEach { branchNode ->
            val branchMessage = InstanceMessage(
                workflowInfo = instance.workflowInfo,
                workflowState = WorkflowCommand.ResumeFromTask(
                    nodeStack = instance.workflowState.nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = instance.workflowState.rawInput
                ),
            )

            // Derive message ID using branch position (unique per branch)
            val branchMessageId = IDV7.deriveFromPositionAndStep(
                baseId = instance.workflowId.value,
                position = branchNode.position,
                step = instance.workflowState.nodeStack.rootState.workflowStep,
                suffix = "-branch-init"
            )

            logger.debug { "Scheduling branch ${branchNode.name} at ${branchNode.position}" }
            instanceEmitter.send(branchMessage, branchMessageId)
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
    private suspend fun handleBranchCompleted(instance: InstanceMessage<WorkflowEvent.ForkBranchCompleted>) {
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
            // Clean error data if branch was previously failed
            branch.failedAt = null
            branch.errorReason = null
            branch.errorClass = null
            branch.errorMessage = null
            branch.errorStackTrace = null

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
                    // Clean error data if fork was previously failed
                    fork.failedAt = null
                    fork.errorReason = null
                    fork.errorClass = null
                    fork.errorMessage = null
                    fork.errorStackTrace = null
                    forkRepository.update(fork, conn)

                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithCompletedTask(
                            nodeStack = instance.workflowState.nodeStack,
                            rawOutput = outputJson,
                        ),
                    )

                    // Derive resume message ID from fork model ID
                    val resumeMessageId = fork.id.derive("-resume-completed")

                    // Emit resume message to workflow channel
                    instanceEmitter.send(resumeMessage, resumeMessageId)
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
    private suspend fun handleBranchFailed(instance: InstanceMessage<WorkflowEvent.ForkBranchFailed>) {
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
            // Store error inline - we don't know yet if this will trigger a workflow failure
            // (the failure can be caught by a try node above the fork)
            branch.failedAt = Clock.System.now()
            branch.errorReason = branchError.type
            branch.errorClass = branchError.type
            branch.errorMessage = branchError.title
            branch.errorStackTrace = branchError.details

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

                    fork.failedAt = branch.failedAt
                    fork.errorReason = branchError.type
                    fork.errorClass = branchError.type
                    fork.errorMessage = branchError.title
                    fork.errorStackTrace = branchError.details

                    // Save the updated fork in the transaction
                    forkRepository.update(fork, conn)

                    // we do not know yet if the workflow will be failing after this fork failure
                    // this exception can be caught above the fork, that's why we restart from there
                    val resumeMessage = InstanceMessage(
                        workflowInfo = instance.workflowInfo,
                        workflowState = WorkflowCommand.ResumeWithFailedTask(
                            nodeStack = instance.workflowState.nodeStack,
                            error = error,
                        ),
                    )

                    // Derive resume message ID from fork model ID
                    val resumeMessageId = fork.id.derive("-resume-failed")
                    instanceEmitter.send(resumeMessage, resumeMessageId)
                }
            }
        }
    }

    /**
     * Calculates correlation values by evaluating `expect` expressions from filters
     * against the correlation context.
     *
     * Returns a JSON string with sorted keys for consistent database comparison,
     * or null if no correlations with expect expressions are defined.
     *
     * @param config The listen configuration containing filters and correlation context
     * @return JSON string of correlation values, or null if no correlations
     */
    private fun calculateCorrelationValues(config: ListenConfig): String? {
        val correlationContext = config.correlationContext ?: return null
        if (correlationContext !is JsonObject) return null

        // Collect all correlation expect expressions from all filters
        val expectExpressions = mutableMapOf<String, String>()
        for (filter in config.filters) {
            filter.correlations?.forEach { (key, correlateValue) ->
                correlateValue.expect?.let { expectExpr ->
                    expectExpressions[key] = expectExpr
                }
            }
        }

        if (expectExpressions.isEmpty()) return null

        // Evaluate each expect expression against the correlation context
        val evaluatedValues = mutableMapOf<String, String>()
        for ((key, expectExpr) in expectExpressions) {
            try {
                val trimmedExpr = if (ExpressionUtils.isExpr(expectExpr)) {
                    ExpressionUtils.trimExpr(expectExpr)
                } else {
                    expectExpr
                }

                val result = with(LemlineJson) {
                    val inputNode = correlationContext.toJsonNode()
                    val scope = JsonObject(emptyMap()).toJsonNode() as com.fasterxml.jackson.databind.node.ObjectNode
                    JQExpression.eval(inputNode, trimmedExpr, scope).toJsonElement()
                }

                val value = when (result) {
                    is JsonPrimitive -> result.contentOrNull
                    else -> result.toString()
                }

                if (value != null) {
                    evaluatedValues[key] = value
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to evaluate correlation expect expression '$expectExpr' for key '$key'" }
            }
        }

        if (evaluatedValues.isEmpty()) return null

        // Serialize with sorted keys for consistent database comparison
        val sortedEntries = evaluatedValues.entries.sortedBy { it.key }
        return buildJsonObject {
            for ((key, value) in sortedEntries) {
                put(key, value)
            }
        }.let { Json.encodeToString(it) }
    }
}
