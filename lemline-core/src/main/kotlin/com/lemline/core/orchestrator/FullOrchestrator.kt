// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.activities.mock.MockActivityExecutor
import com.lemline.core.definitions.DefinitionCache
import com.lemline.core.definitions.getNode
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.utils.mapAwaitAllFailFast
import com.lemline.core.utils.mapAwaitFirstFailSlow
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Full orchestrator for synchronous workflow execution.
 *
 * This orchestrator executes workflows in a single pass, handling all events
 * (activities, waits, forks, child workflows) directly without external coordination.
 */
@ExperimentalTime
internal object FullOrchestrator {

    private val logger = logger()

    /** Default mock activity executor - requires explicit mock configuration for activities */
    private val defaultActivityExecutor = MockActivityExecutor.empty()

    suspend fun start(
        workflow: Workflow,
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonElement = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
        serde: Boolean = false,
        activityExecutor: ActivityExecutor = defaultActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        val cmd = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent, startedAt)

        return resume(workflow, cmd, serde, activityExecutor, lifecycleHook).value()
    }

    suspend fun resume(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor = defaultActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome {

        val serdeCommand = when (serde) {
            true -> WorkflowState.fromJsonString(command.toJsonString()) as WorkflowCommand
            false -> command
        }

        if (command != serdeCommand)
            throw IllegalStateException("Command mismatch\ncommand     : $command\nserdeCommand: $serdeCommand")

        val event = StepByStepOrchestrator.runByTask(workflow, serdeCommand, workflow.info, lifecycleHook)

        val serdeEvent = when (serde) {
            true -> WorkflowState.fromJsonString(event.toJsonString()) as WorkflowEvent
            false -> event
        }

        if (event != serdeEvent)
            throw IllegalStateException("Event mismatch\nevent     : $event\nserdeEvent: $serdeEvent")

        return when (serdeEvent) {
            is WorkflowEvent.ActivityStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, activityExecutor),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.WaitStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook
            )

            is WorkflowEvent.TaskScheduled -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook
            )

            is WorkflowEvent.TaskRetryScheduled -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook
            )

            is WorkflowEvent.RunWorkflowStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, serde, activityExecutor, lifecycleHook),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.ForkStarted -> resume(
                workflow = workflow,
                command = handle(workflow, serdeEvent, serde, activityExecutor, lifecycleHook),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.ListenStarted -> resume(
                workflow = workflow,
                command = handle(serdeEvent, activityExecutor),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.ListenForEachCompleted -> resume(
                workflow = workflow,
                command = handle(serdeEvent),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            )

            is WorkflowEvent.Outcome -> serdeEvent
        }
    }

    /**
     * Handles an ActivityStarted event by executing the activity and resuming with the result.
     *
     * Activities are executed via the ActivityExecutor interface, which allows for
     * different implementations (real I/O vs mocks for testing).
     */
    private suspend fun handle(
        event: WorkflowEvent.ActivityStarted,
        activityExecutor: ActivityExecutor
    ): WorkflowCommand {
        logger.debug { "Executing activity: ${event::class.simpleName}" }
        return try {
            val output = activityExecutor.execute(event)
            logger.debug { "Activity completed: ${event::class.simpleName}" }
            event.resumeCompleted(output)
        } catch (e: Exception) {
            logger.error(e) { "Activity failed: ${event::class.simpleName}" }
            event.resumeFailed(InternalException.Error.from(e, event.nodePosition))
        }
    }

    /**
     * Handles a retry event by delaying execution until the scheduled retry time
     * and then resuming the workflow from the specified task.
     */
    private suspend fun handle(event: WorkflowEvent.TaskRetryScheduled): WorkflowCommand {
        val delayDuration = event.retryAt - Clock.System.now()
        logger.debug { "Retrying in $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Retrying" }
        return event.resume()
    }

    /**
     * Handles a ListenStarted event using mock configuration.
     * Returns mock CloudEvent data if the activity executor has listen mocks configured.
     */
    private fun handle(
        event: WorkflowEvent.ListenStarted,
        activityExecutor: ActivityExecutor
    ): WorkflowCommand {
        // Get mock config from MockActivityExecutor if available
        val mockConfig = (activityExecutor as? MockActivityExecutor)?.mockConfig
            ?: throw UnsupportedOperationException(
                "ListenStarted events require mock configuration or external CloudEvent coordination. " +
                    "Use MockActivityExecutor with listen mocks or StepByStepOrchestrator with runner infrastructure."
            )

        // Get the event type filter from the first filter in the listen config
        val eventTypeFilter = event.config.filters.firstOrNull()?.type ?: "*"
        val mock = mockConfig.findListenMock(eventTypeFilter)
            ?: throw UnsupportedOperationException(
                "No listen mock found for event type: $eventTypeFilter. " +
                    "Add a listen mock to MockConfiguration to test listen tasks."
            )

        logger.debug { "Mock: Listen mock matched for type=$eventTypeFilter" }

        if (mock.response.error != null) {
            return event.resumeFailed(
                InternalException.Error.from(
                    RuntimeException(mock.response.error),
                    event.nodePosition
                )
            )
        }

        // Return mock data as a single-element JsonArray (listen returns array of events)
        return event.resumeCompleted(JsonArray(listOf(mock.response.data ?: buildJsonObject { })))
    }

    /**
     * Handles a ListenForEachCompleted event by resuming with the iteration output.
     *
     * In mock mode, this assumes a single-event scenario where one foreach iteration
     * completes and the listen task should resume with that output wrapped in an array.
     */
    private fun handle(event: WorkflowEvent.ListenForEachCompleted): WorkflowCommand {
        logger.debug { "Mock: ListenForEachCompleted iteration=${event.iterationIndex}" }
        // Resume with the single iteration output wrapped in an array
        return event.resumeCompleted(JsonArray(listOf(event.iterationOutput)))
    }

    /**
     * Handles a `RunWorkflowStarted` event by initiating the corresponding child workflow either
     * synchronously or asynchronously, depending on its configuration.
     */
    private suspend fun handle(
        event: WorkflowEvent.RunWorkflowStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        // Retrieve child workflow definition
        val childWorkflow = DefinitionCache.getWorkflow(
            namespace = event.config.namespace,
            name = event.config.name,
            version = event.config.version
        ) ?: throw IllegalStateException("Workflow definition not found")

        return when (event.config.sync) {
            true -> {
                // synchronous execution
                val initCmd = StepByStepOrchestrator.initCmd(
                    workflowInput = event.config.input,
                    hasWaitingParent = true
                )
                val result = resume(childWorkflow, initCmd, serde, activityExecutor, lifecycleHook)
                logger.debug { "Child workflow completed" }
                when (result) {
                    is WorkflowEvent.WorkflowCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.ForkBranchCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    is WorkflowEvent.ForkBranchFailed -> event.resumeAsFailed(result.error)
                }
            }

            false -> {
                // asynchronous execution
                CoroutineScope(currentCoroutineContext()).launch {
                    val initCmd = StepByStepOrchestrator.initCmd(
                        workflowInput = event.config.input,
                        hasWaitingParent = false
                    )
                    resume(childWorkflow, initCmd, serde, activityExecutor, lifecycleHook) // <= output is not handled
                    logger.debug { "Child workflow completed" }
                }
                // Immediate resuming
                event.resumeAsync()
            }
        }
    }

    /**
     * Handles the provided `TaskScheduled` event by resuming the workflow from the next task.
     */
    private fun handle(event: WorkflowEvent.TaskScheduled): WorkflowCommand = event.resume()

    /**
     * Handles the provided `WaitStarted` event by waiting until the specified time
     * and then resumes the workflow from the started task.
     */
    private suspend fun handle(event: WorkflowEvent.WaitStarted): WorkflowCommand {
        val delayDuration = event.config.waitUntil - Clock.System.now()
        logger.debug { "Waiting for $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Waiting completed" }

        return event.resume()
    }

    /**
     * Handles the `ForkStarted` event during workflow execution by determining the type of fork
     * operation (compete or cooperative), executing the corresponding branches, and resuming
     * the workflow with the computed output.
     */
    private suspend fun handle(
        workflow: Workflow,
        event: WorkflowEvent.ForkStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        @Suppress("UNCHECKED_CAST")
        val forkNode = workflow.getNode(event.nodePosition) as Node<ForkTask>

        val branches = forkNode.children
            ?: throw IllegalStateException("Fork node in ${forkNode.position} has no branches")

        // Execute branches and get the result
        return try {
            val output = if (forkNode.task.fork.isCompete) {
                workflow.executeCompete(
                    event.nodeStack,
                    branches,
                    event.rawInput,
                    serde,
                    activityExecutor,
                    lifecycleHook
                )
            } else {
                workflow.executeCooperative(
                    event.nodeStack,
                    branches,
                    event.rawInput,
                    serde,
                    activityExecutor,
                    lifecycleHook
                )
            }
            logger.debug { "Fork completed: output=$output" }
            WorkflowCommand.ResumeWithCompletedTask(
                nodeStack = event.nodeStack,
                rawOutput = output,
            )
        } catch (e: InternalException) {
            logger.error { "Fork failed: error=$e" }
            WorkflowCommand.ResumeWithFailedTask(
                nodeStack = event.nodeStack,
                error = InternalException.Error.from(e, forkNode.position)
            )
        }
    }

    /**
     * Execute fork branches in compete mode (race for first completion).
     *
     * Returns the output from the first branch to complete successfully.
     * Throws an exception if all branches fail.
     */
    private suspend fun Workflow.executeCompete(
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonElement,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        // Get the first success - if all branches failed, the last exception will be rethrown from here
        return branches.mapAwaitFirstFailSlow { branchNode ->
            lifecycleHook.onTaskCreated(
                workflowInfo = info,
                nodeStack = nodeStack,
                nodePosition = branchNode.position,
                input = rawInput,
                createdAt = Clock.System.now(),
            )

            resume(
                workflow = this,
                command = WorkflowCommand.ResumeFromTask(
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = rawInput,
                    flowDirective = null
                ),
                serde = serde,
                activityExecutor = activityExecutor,
                lifecycleHook = lifecycleHook,
            ).value()
        }
    }

    /**
     * Execute fork branches in cooperative mode (wait for all).
     *
     * Returns an array containing outputs from all branches.
     * Throws an exception for the first branch failing.
     */
    private suspend fun Workflow.executeCooperative(
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonElement,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        lifecycleHook: LifecycleEventHook,
    ): JsonArray {
        // Get all results - If a branch failed, the first exception will be rethrown from here
        return JsonArray(
            branches.mapAwaitAllFailFast { branchNode ->
                lifecycleHook.onTaskCreated(
                    workflowInfo = info,
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    input = rawInput,
                    createdAt = Clock.System.now(),
                )

                resume(
                    workflow = this,
                    command = WorkflowCommand.ResumeFromTask(
                        nodeStack = nodeStack,
                        nodePosition = branchNode.position,
                        rawInput = rawInput,
                        flowDirective = null
                    ),
                    serde = serde,
                    activityExecutor = activityExecutor,
                    lifecycleHook = lifecycleHook,
                ).value()
            })
    }
}
