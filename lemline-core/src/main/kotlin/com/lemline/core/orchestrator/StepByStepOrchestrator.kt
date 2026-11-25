// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.core.errors.InternalException
import com.lemline.core.errors.StepExecutionException
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.StepByStepOrchestrator.completeTask
import com.lemline.core.orchestrator.StepByStepOrchestrator.processTaskFaulted
import com.lemline.core.orchestrator.StepByStepOrchestrator.tryCatch
import com.lemline.core.processors.RootProcessor
import com.lemline.core.processors.TryProcessor
import com.lemline.core.processors.scope.taskScope
import com.lemline.core.states.ExtensionPhase
import com.lemline.core.states.ExtensionReturn
import com.lemline.core.states.NodeStack
import com.lemline.core.states.PendingTaskState
import com.lemline.core.states.RootState
import com.lemline.core.states.StackFrame
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.ForEachCompleted
import com.lemline.core.states.WorkflowEvent.ForkBranchFaulted
import com.lemline.core.states.WorkflowEvent.ForkStarted
import com.lemline.core.states.WorkflowEvent.ListenStarted
import com.lemline.core.states.WorkflowEvent.Outcome
import com.lemline.core.states.WorkflowEvent.RunWorkflowStarted
import com.lemline.core.states.WorkflowEvent.TaskRetryScheduled
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.states.WorkflowEvent.WaitStarted
import com.lemline.core.states.WorkflowEvent.WorkflowCompleted
import com.lemline.core.states.WorkflowEvent.WorkflowFaulted
import com.lemline.core.tasks.toJava
import com.lemline.core.tasks.toKotlin
import com.lemline.core.workflows.NodeResolutionException
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.getNode
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.Task
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * The StepByStepOrchestrator object handles the orchestration of workflow execution,
 * managing task states, and processing commands to resume or complete various
 * tasks and activities within a workflow context.
 *
 * This orchestrator provides methods to initiate a workflow, execute commands at both
 * task and activity levels, and manage task transitions during workflow execution.
 * Internally, it handles resuming workflows that involve both successful task completions
 * and error states.
 */
object StepByStepOrchestrator {

    /**
     * Provides a `WorkflowCommand` starting a workflow.
     */
    fun initCmd(
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonNode = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
    ): WorkflowCommand.ResumeFromTask {
        val rootState = RootState(
            startedAt = startedAt,
            workflowId = workflowId,
            workflowInput = workflowInput,
            hasWaitingParent = hasWaitingParent,
        )
        val nodeStack = NodeStack(listOf(StackFrame(NodePosition.root, rootState)))

        return WorkflowCommand.ResumeFromTask(
            nodePosition = NodePosition.doRoot,
            rawInput = workflowInput,
            nodeStack = nodeStack,
            flowDirective = null,
        )
    }

    /**
     * Processes a given workflow command and resumes workflow execution based on the command's type.
     *
     * @param workflow The workflow definition
     * @param command The command to execute
     * @param workflowInfo The workflow identity (namespace, name, version)
     * @param lifecycleHook Optional hook for lifecycle event callbacks (default: no-op)
     * @param functionResolver Resolver for remote function references (URLs, catalogs)
     */
    suspend fun runByTask(
        workflow: Workflow,
        command: WorkflowCommand,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        functionResolver: suspend (String) -> Task = { error("Function resolver not configured") },
        secrets: ObjectNode = buildJsonObject {},
    ): WorkflowEvent {
        // RootStateProto omits the definition; restore it from the loaded workflow before evaluating scope.
        val declaredSecrets = workflow.use?.secrets.orEmpty()
        val visibleSecrets = ObjectNode(secrets.filterKeys { it in declaredSecrets })
        val nodeStack = command.nodeStack
            .withWorkflowDefinition(WorkflowCache.getDefinitionJson(workflow))
            .withSecrets(visibleSecrets)
        declaredSecrets.firstOrNull { !visibleSecrets.has(it) }?.let { missing ->
            val event = WorkflowFaulted(
                nodeStack = nodeStack,
                rawInput = null,
                rawOutput = null,
                flowDirective = null,
                error = InternalException.Error(
                    errorType = WorkflowErrorType.AUTHORIZATION,
                    position = command.nodePosition,
                    title = "Required secret '$missing' is unavailable",
                ),
                faultedAt = Clock.System.now(),
            )
            emitLifecycleEventsForEvent(event, workflowInfo, lifecycleHook)
            return event
        }
        var faultedTaskPosition = command.nodePosition
        val event = try {
            val node = workflow.getNode(command.nodePosition, functionResolver)
            when (command) {
                is WorkflowCommand.ResumeFromTask -> when (node.isWorkflowEntry(nodeStack)) {
                    true -> startWorkflow(workflow, nodeStack, node, command, workflowInfo, lifecycleHook, functionResolver)
                    false -> resumeFromTask(
                        nodeStack = nodeStack,
                        node = node,
                        rawInput = command.rawInput,
                        flowDirective = command.flowDirective?.toJava(),
                        extensionReturn = command.extensionReturn,
                        workflowInfo = workflowInfo,
                        lifecycleHook = lifecycleHook,
                    )
                }

                is WorkflowCommand.ResumeWithCompletedTask -> resumeFromCompletedTask(
                    nodeStack = nodeStack,
                    node = node,
                    rawOutput = command.rawOutput,
                    workflowInfo = workflowInfo,
                    lifecycleHook = lifecycleHook,
                )

                is WorkflowCommand.ResumeWithFaultedTask -> resumeFromFaultedTask(
                    nodeStack = nodeStack,
                    node = node,
                    error = command.error,
                    workflowInfo = workflowInfo,
                    lifecycleHook = lifecycleHook,
                )
            }

        } catch (e: NodeResolutionException) {
            faultedTaskPosition = e.caller.position
            resumeWithHandler(nodeStack, e.caller, null, null, null, workflowInfo, lifecycleHook) {
                throw InternalException(InternalException.Error.from(e.failure, e.caller.position))
            }
        }

        emitLifecycleEventsForEvent(event, workflowInfo, lifecycleHook, faultedTaskPosition)

        // Failed selection/initialization has no resumable continuation. Close lifecycle
        // events above before dropping its transient pending frame from the terminal message.
        return if (event is WorkflowFaulted) event.copy(nodeStack = NodeStack(event.nodeStack.filter { it.state !is PendingTaskState })) else event
    }

    /** True when the workflow is about to enter its first task: `/do` has no frame yet. */
    private fun Node<*>.isWorkflowEntry(nodeStack: NodeStack) =
        position == NodePosition.doRoot && nodeStack[position] == null

    /**
     * Starts the workflow: applies the workflow-level `input.from`, then enters `/do` with the
     * result. `$workflow.input` keeps the original input.
     *
     * A failing `input.from` faults the workflow at its root, since no task has started yet;
     * `workflow.started` is still emitted first so that the lifecycle stays well-formed.
     */
    private suspend fun startWorkflow(
        workflow: Workflow,
        nodeStack: NodeStack,
        doNode: Node<*>,
        command: WorkflowCommand.ResumeFromTask,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        functionResolver: suspend (String) -> Task,
    ): WorkflowEvent {
        val rootProcessor = workflow.getNode(NodePosition.root, functionResolver).processor as RootProcessor
        val input = try {
            rootProcessor.transformWorkflowInput(nodeStack, command.rawInput)
        } catch (e: InternalException) {
            val faultedAt = Clock.System.now()
            lifecycleHook.onWorkflowStarted(workflowInfo, nodeStack, faultedAt)
            return WorkflowFaulted(nodeStack, command.rawInput, null, null, e.error, faultedAt)
        }
        return resumeFromTask(
            nodeStack = nodeStack,
            node = doNode,
            rawInput = input,
            flowDirective = command.flowDirective?.toJava(),
            extensionReturn = command.extensionReturn,
            workflowInfo = workflowInfo,
            lifecycleHook = lifecycleHook,
        )
    }

    /**
     * Emits lifecycle events based on the workflow event type.
     *
     * This handles workflow-level events and task scheduling. Task completion/failure
     * events are emitted closer to where they occur:
     * - `task.completed` in [completeTask] for async completions
     * - `task.faulted` in [tryCatch] when errors are caught
     * - `task.retried` in [processTaskFaulted] when retry is scheduled
     *
     * Event mapping:
     * - [WorkflowCompleted] → workflow.completed
     * - [WorkflowFaulted] → workflow.faulted
     * - [TaskScheduled] → task.created (only when scheduling a child, not returning to parent)
     */
    private suspend fun emitLifecycleEventsForEvent(
        workflowEvent: WorkflowEvent,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        faultedTaskPosition: NodePosition? = null,
    ) {
        when (workflowEvent) {
            is WorkflowCompleted -> {
                lifecycleHook.onWorkflowCompleted(
                    workflowInfo = workflowInfo,
                    nodeStack = workflowEvent.nodeStack,
                    output = workflowEvent.output,
                    completedAt = workflowEvent.completedAt,
                )
            }

            is WorkflowFaulted -> {
                // The stack contains the active ancestors. The current task has already
                // emitted task.faulted in tryCatch or the plain-exception handler.
                for (frame in workflowEvent.nodeStack.toList().asReversed()) {
                    if (frame.position == NodePosition.root || frame.position == faultedTaskPosition) continue
                    lifecycleHook.onTaskFaulted(
                        workflowInfo = workflowInfo,
                        nodeStack = workflowEvent.nodeStack,
                        nodePosition = frame.position,
                        error = workflowEvent.error,
                        faultedAt = workflowEvent.faultedAt,
                    )
                }
                lifecycleHook.onWorkflowFaulted(
                    workflowInfo = workflowInfo,
                    nodeStack = workflowEvent.nodeStack,
                    error = workflowEvent.error,
                    faultedAt = workflowEvent.faultedAt,
                )
            }

            is TaskScheduled -> {
                // Emit task.created only when scheduling a new task
                if (workflowEvent.isNew) {
                    lifecycleHook.onTaskCreated(
                        workflowInfo = workflowInfo,
                        nodeStack = workflowEvent.nodeStack,
                        nodePosition = workflowEvent.nodePosition,
                        input = workflowEvent.rawInput,
                        createdAt = Clock.System.now()
                    )
                }
            }

            else -> {
                /* No lifecycle event for other event types */
            }
        }
    }

    /**
     * Executes the workflow based on the provided command, processing activity nodes recursively
     * until an appropriate event is returned.
     *
     * @param workflow The workflow definition
     * @param command The command to execute
     * @param workflowInfo The workflow identity (namespace, name, version)
     * @param lifecycleHook Optional hook for lifecycle event callbacks (default: no-op)
     * @param functionResolver Resolver for remote function references (URLs, catalogs)
     */
    suspend fun runByActivity(
        workflow: Workflow,
        command: WorkflowCommand,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        functionResolver: suspend (String) -> Task = { error("Function resolver not configured") },
        secrets: ObjectNode = buildJsonObject {},
    ): WorkflowEvent {

        return when (val event = runByTask(workflow, command, workflowInfo, lifecycleHook, functionResolver, secrets)) {
            is TaskScheduled -> if (try { workflow.getNode(event.nodePosition, functionResolver).isActivity() }
                catch (_: NodeResolutionException) { false }) {
                event
            } else {
                runByActivity(workflow, event.resume(), workflowInfo, lifecycleHook, functionResolver, secrets)
            }

            is ActivityStarted -> event
            is WaitStarted -> event
            is ListenStarted -> event
            is ForEachCompleted -> event
            is TaskRetryScheduled -> event
            is RunWorkflowStarted -> event
            is ForkStarted -> event
            is Outcome -> event
        }
    }

    private suspend fun resumeWithHandler(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonNode?,
        rawOutput: JsonNode?,
        flowDirective: FlowDirective?,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        action: suspend () -> WorkflowEvent,
    ): WorkflowEvent {
        val failure: Exception
        val stack: NodeStack
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: StepExecutionException) {
            stack = e.nodeStack
            failure = e.failure
        } catch (e: Exception) {
            stack = nodeStack
            failure = e
        }
        val error = InternalException.Error.from(failure, node.position)
        lifecycleHook.onTaskFaulted(workflowInfo, stack, node.position, error, Clock.System.now())
        return try {
            processTaskFaulted(InternalException(error), node, stack, workflowInfo, lifecycleHook)
        } catch (e: Exception) {
            hasForkBranchFaulted(stack, node, e, workflowInfo, lifecycleHook)
                ?: WorkflowFaulted(nodeStack = stack, nodePosition = node.position, rawInput = rawInput,
                    rawOutput = rawOutput, flowDirective = flowDirective?.toKotlin(), exception = e, faultedAt = Clock.System.now())
        }
    }

    /**
     * Resumes the workflow execution from a specific task.
     *
     * Emits task.started lifecycle event when a task begins execution.
     */
    internal suspend fun resumeFromTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonNode,
        flowDirective: FlowDirective? = null,
        extensionReturn: ExtensionReturn? = null,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent = resumeWithHandler(
        nodeStack = nodeStack,
        node = node,
        rawInput = rawInput,
        rawOutput = null,
        flowDirective = flowDirective,
        workflowInfo = workflowInfo,
        lifecycleHook = lifecycleHook
    ) {
        startTask(nodeStack, node, rawInput, flowDirective, workflowInfo, lifecycleHook, extensionReturn)
    }

    /**
     * Resumes the workflow execution from an asynchronously completed task,
     * (transitioning the current task from an incomplete state to the next expected state.)
     *
     * Emits task.completed lifecycle event when task finishes successfully.
     */
    internal suspend fun resumeFromCompletedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonNode,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent = resumeWithHandler(
        nodeStack = nodeStack,
        node = node,
        rawInput = null,
        rawOutput = rawOutput,
        flowDirective = null,
        workflowInfo = workflowInfo,
        lifecycleHook = lifecycleHook
    ) {
        completeTask(nodeStack, node, rawOutput, workflowInfo, lifecycleHook)
    }

    /**
     * Resumes the workflow execution from an asynchronously faulted task,
     * (hopefully catching the error, if not returns a ForkBranchFaulted or TaskFaulted event on the same node)
     *
     * The task.faulted event is emitted in [tryCatch] where the error is caught.
     */
    internal suspend fun resumeFromFaultedTask(
        nodeStack: NodeStack,
        node: Node<*>,
        error: InternalException.Error,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent = resumeWithHandler(nodeStack, node, null, null, null, workflowInfo, lifecycleHook) {
        throw InternalException(error)
    }

    /**
     * Executes a given block of code within a try-catch construct.
     * If the block execution succeeds, the result is returned.
     * If it fails with an `InternalWorkflowException`, the workflow is trying to resume through a parent `try` node.
     *
     * Emits `task.faulted` when an error is caught (regardless of whether it's handled or re-thrown).
     */
    suspend fun tryCatch(
        current: Node<*>,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        block: suspend () -> WorkflowEvent
    ): WorkflowEvent = try {
        block()
    } catch (e: StepExecutionException) {
        tryCatch(current, e.nodeStack, workflowInfo, lifecycleHook) { throw e.failure }
    } catch (e: InternalException) {
        // Emit task.faulted - the task faulted with an error
        lifecycleHook.onTaskFaulted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = current.position,
            error = e.error,
            faultedAt = Clock.System.now(),
        )
        processTaskFaulted(e, current, nodeStack, workflowInfo, lifecycleHook)
    }

    /**
     * Handle exception by finding a TryTask and returning the appropriate state transition.
     *
     * Walks up the node tree looking for a TryTask that can handle the error.
     * Stops at error boundaries:
     * - Fork tasks act as error boundaries and handle errors internally
     * - Foreach tasks act as error boundaries for foreach iterations
     *
     * Emits `task.retried` when a retry is scheduled.
     */
    private suspend fun processTaskFaulted(
        exception: InternalException,
        failingNode: Node<*>,
        nodeStack: NodeStack,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        // Find the nearest TryTask - within the same error boundary - that can handle this error
        var current: Node<*>? = failingNode

        do {
            // if we reached a try task, we check if this error can be handled by it
            // if yes, we continue from there (retry or catch)
            val frame = nodeStack[current!!.position]
            if (current.task is TryTask && frame?.state is TryState &&
                (frame.extension == null || frame.extension.phase == ExtensionPhase.BODY) && current != failingNode) {
                val tryState = frame.state
                val processor = current.processor as TryProcessor
                // check that this node actually can handle this error
                if (processor.isCatching(exception.error, tryState, processor.filterScope(tryState, nodeStack))) {
                    val event = processor.handleError(
                        error = exception.error,
                        state = tryState,
                        nodeStack = nodeStack
                    )
                    // Close abandoned invocations before their frames disappear, including extension owners.
                    for (abandoned in nodeStack.toList().asReversed().takeWhile { it.position != current.position }) {
                        if (abandoned.position == failingNode.position) continue
                        lifecycleHook.onTaskFaulted(workflowInfo, nodeStack, abandoned.position, exception.error, Clock.System.now())
                    }
                    // Emit task.retried if a retry was scheduled
                    if (event is TaskRetryScheduled) {
                        lifecycleHook.onTaskRetried(
                            workflowInfo = workflowInfo,
                            nodeStack = event.nodeStack,
                            nodePosition = event.nodePosition,
                            retryAt = event.retryAt,
                            attemptNumber = tryState.attemptIndex + 1,
                        )
                    }
                    return event
                }
            }
            val child = current
            current = current.parent
            if (current?.task is ForkTask && current.children.orEmpty().any { it.position == child.position }) break
        } while (current != null)

        // No handler found - fail workflow
        throw exception
    }

    /**
     * Execute a single step of workflow execution.
     *
     * Determines whether we enter the node for the first time (stack top is parent)
     * or re-enter after child completion (stack top is this node).
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun startTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonNode,
        flowDirective: FlowDirective?,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
        extensionReturn: ExtensionReturn? = null,
    ): WorkflowEvent {
        val isFirstEntry = nodeStack[node.position] == null

        require(!isFirstEntry || extensionReturn == null) { "Extension return has no owner frame at ${node.position}" }
        // Emit entry events (workflow.started, task.created, task.started)
        if (isFirstEntry) {
            emitTaskEntryEvents(nodeStack, node, rawInput, workflowInfo, lifecycleHook)
        }

        // Execute the task
        val event = if (isFirstEntry) {
            node.processor.enterFromParent(nodeStack, workflowInfo, rawInput, lifecycleHook)
        } else {
            node.processor.reEnterFromChild(nodeStack, workflowInfo, rawInput, flowDirective, lifecycleHook, extensionReturn)
        }

        return event
    }

    /**
     * Emits lifecycle events when entering a task for the first time.
     *
     * Events emitted:
     * - `workflow.started` when entering `/do` (first task after root)
     * - `task.created` for `/do` (since it's not scheduled via TaskScheduled)
     * - `task.started` for all tasks
     */
    private suspend fun emitTaskEntryEvents(
        nodeStack: NodeStack,
        node: Node<*>,
        rawInput: JsonNode,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ) {
        val now = Clock.System.now()

        // Emit workflow.started when entering /do (first task after root)
        if (node.position == NodePosition.doRoot) {
            lifecycleHook.onWorkflowStarted(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                startedAt = now,
            )
            // Also emit task.created for /do since it's the first task
            // (not scheduled via TaskScheduled event)
            lifecycleHook.onTaskCreated(
                workflowInfo = workflowInfo,
                nodeStack = nodeStack,
                nodePosition = node.position,
                input = rawInput,
                createdAt = now,
            )
        }

        // Emit task.started for all nodes (including /do)
        lifecycleHook.onTaskStarted(
            workflowInfo = workflowInfo,
            nodeStack = nodeStack,
            nodePosition = node.position,
            rawInput = rawInput,
            startedAt = now,
        )
    }

    /**
     * Completes an interrupted task by processing the output through the current node's processor.
     *
     * Emits `task.completed` after the task completes, with the transformed output.
     */
    internal suspend fun completeTask(
        nodeStack: NodeStack,
        node: Node<*>,
        rawOutput: JsonNode,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent {
        val scope = nodeStack.taskScope(node)

        val event = node.processor.completeTask(
            rawOutput = rawOutput,
            currentFlowDirective = node.processor.getFlowDirective(),
            currentScope = scope,
            nodeStack = nodeStack,
            workflowInfo = workflowInfo,
            lifecycleHook = lifecycleHook
        )

        return event
    }

    /**
     * Handles the failure of a branch execution stemming from a Fork task in a workflow (if any)
     */
    private suspend fun hasForkBranchFaulted(
        nodeStack: NodeStack,
        failingNode: Node<*>,
        exception: Exception,
        workflowInfo: WorkflowInfo,
        lifecycleHook: LifecycleEventHook,
    ): ForkBranchFaulted? {
        // Find the nearest Fork task up the current node
        var current: Node<*> = failingNode

        while (current.parent != null) {
            val forkNode = current.parent

            if (forkNode.task is ForkTask && forkNode.children.orEmpty().any { it.position == current.position }) {
                val faultedAt = Clock.System.now()
                val error = InternalException.Error.from(exception, failingNode.position)
                for (frame in nodeStack.toList().asReversed().takeWhile { it.position != forkNode.position }) {
                    if (frame.position == failingNode.position) continue
                    lifecycleHook.onTaskFaulted(
                        workflowInfo = workflowInfo,
                        nodeStack = nodeStack,
                        nodePosition = frame.position,
                        error = error,
                        faultedAt = faultedAt,
                    )
                }
                // Pop all states up to and including the fork node
                return ForkBranchFaulted(
                    nodeStack = nodeStack.popUntil(forkNode.position).incrementTopCounter(),
                    branchPosition = current.position,
                    error = error,
                    faultedAt = faultedAt,
                )
            }
            current = current.parent
        }
        return null
    }
}
