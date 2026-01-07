// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator

import com.lemline.common.logger.logger
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventFactory
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.orchestrator.full.CollectResult
import com.lemline.core.orchestrator.full.ForkBranchExecutor
import com.lemline.core.orchestrator.full.ListenEventCollector
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.core.workflows.WorkflowCache
import com.lemline.core.workflows.branches
import com.lemline.core.workflows.foreachBlock
import com.lemline.core.workflows.getNode
import io.serverlessworkflow.api.types.ForkTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
object FullOrchestrator {

    private val logger = logger()

    suspend fun start(
        workflow: Workflow,
        workflowId: WorkflowId = WorkflowId.random(),
        workflowInput: JsonElement = buildJsonObject { },
        hasWaitingParent: Boolean = false,
        startedAt: Instant = Clock.System.now(),
        serde: Boolean = false,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): JsonElement {
        val cmd = StepByStepOrchestrator.initCmd(workflowId, workflowInput, hasWaitingParent, startedAt)
        return resume(workflow, cmd, serde, activityExecutor, cloudEventHook, lifecycleHook).value()
    }

    suspend fun resume(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome {
        val serdeCommand = validateSerde(command, serde)
        val event = StepByStepOrchestrator.runByTask(workflow, serdeCommand, workflow.info, lifecycleHook)
        val serdeEvent = validateSerde(event, serde)

        return handleEvent(serdeEvent, workflow, serde, activityExecutor, cloudEventHook, lifecycleHook)
    }

    private fun validateSerde(command: WorkflowCommand, serde: Boolean): WorkflowCommand {
        if (!serde) return command
        val roundTripped = WorkflowState.fromJsonString(command.toJsonString()) as WorkflowCommand
        if (command != roundTripped) {
            throw IllegalStateException("Command mismatch\ncommand     : $command\nserdeCommand: $roundTripped")
        }
        return roundTripped
    }

    private fun validateSerde(event: WorkflowEvent, serde: Boolean): WorkflowEvent {
        if (!serde) return event
        val roundTripped = WorkflowState.fromJsonString(event.toJsonString()) as WorkflowEvent
        if (event != roundTripped) {
            throw IllegalStateException("Event mismatch\nevent     : $event\nserdeEvent: $roundTripped")
        }
        return roundTripped
    }

    private suspend fun handleEvent(
        event: WorkflowEvent,
        workflow: Workflow,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome = when (event) {
        is WorkflowEvent.ActivityStarted -> resumeWith(
            workflow, handleActivityStarted(event, activityExecutor),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.EmitStarted -> resumeWith(
            workflow, handleEmitStarted(event, cloudEventHook),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.WaitStarted -> resumeWith(
            workflow, handleWaitStarted(event),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.TaskScheduled -> resumeWith(
            workflow, event.resume(),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.TaskRetryScheduled -> resumeWith(
            workflow, handleTaskRetryScheduled(event),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.RunWorkflowStarted -> resumeWith(
            workflow, handleRunWorkflowStarted(event, serde, activityExecutor, cloudEventHook, lifecycleHook),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.ForkStarted -> resumeWith(
            workflow, handleForkStarted(workflow, event, serde, activityExecutor, cloudEventHook, lifecycleHook),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.ListenStarted -> resumeWith(
            workflow, handleListenStarted(workflow, event, serde, activityExecutor, cloudEventHook, lifecycleHook),
            serde, activityExecutor, cloudEventHook, lifecycleHook
        )

        is WorkflowEvent.Outcome -> event
    }

    private suspend fun resumeWith(
        workflow: Workflow,
        command: WorkflowCommand,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowEvent.Outcome = resume(workflow, command, serde, activityExecutor, cloudEventHook, lifecycleHook)

    private suspend fun handleActivityStarted(
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

    private suspend fun handleTaskRetryScheduled(event: WorkflowEvent.TaskRetryScheduled): WorkflowCommand {
        val delayDuration = event.retryAt - Clock.System.now()
        logger.debug { "Retrying in $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Retrying" }
        return event.resume()
    }

    private suspend fun handleEmitStarted(
        event: WorkflowEvent.EmitStarted,
        cloudEventHook: CloudEventHook
    ): WorkflowCommand {
        logger.debug { "Emitting CloudEvent: type=${event.config.type} source=${event.config.source}" }
        val cloudEvent = CloudEventFactory.build(event.config)
        cloudEventHook.emit(cloudEvent)
        return event.resume()
    }

    private suspend fun handleWaitStarted(event: WorkflowEvent.WaitStarted): WorkflowCommand {
        val delayDuration = event.config.waitUntil - Clock.System.now()
        logger.debug { "Waiting for $delayDuration" }
        if (delayDuration > Duration.ZERO) delay(delayDuration)
        logger.debug { "Waiting completed" }
        return event.resume()
    }

    private suspend fun handleListenStarted(
        workflow: Workflow,
        listenStarted: WorkflowEvent.ListenStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        logger.debug { "Listening for CloudEvents: strategy=${listenStarted.config.strategy} filters=${listenStarted.config.filters} until=${listenStarted.config.until}" }

        @Suppress("UNCHECKED_CAST")
        val listenNode = workflow.getNode(listenStarted.nodePosition) as Node<ListenTask>

        val foreachProcessor = listenNode.foreachBlock?.let { foreachBlock ->
            ListenEventCollector.createForeachProcessor(foreachBlock.position, foreachBlock.task) { cmd ->
                resume(workflow, cmd, serde, activityExecutor, cloudEventHook, lifecycleHook)
            }
        }

        val timeoutMillis = listenStarted.config.timeoutAt?.let {
            (it - Clock.System.now()).inWholeMilliseconds.coerceAtLeast(0)
        } ?: Long.MAX_VALUE

        return try {
            when (val result = withTimeout(timeoutMillis) {
                ListenEventCollector.collect(listenStarted, cloudEventHook.receive(), foreachProcessor)
            }) {
                is CollectResult.Success -> listenStarted.resumeCompleted(JsonArray(result.outputs))
                is CollectResult.Failure -> listenFailed(result.nodeStack, listenStarted.nodePosition, result.error)
            }
        } catch (_: TimeoutCancellationException) {
            val e = IllegalStateException("Listen timeout: no matching CloudEvent received within ${timeoutMillis}ms")
            listenFailed(listenStarted.nodeStack, listenStarted.nodePosition, e)
        }
    }

    private fun listenFailed(nodeStack: NodeStack, position: NodePosition, e: Exception): WorkflowCommand {
        logger.debug(e) { "Listen failed" }
        return WorkflowCommand.ResumeWithFailedTask(
            nodeStack = nodeStack.popUntil(position),
            error = InternalException.Error.from(e, position)
        )
    }

    private suspend fun handleRunWorkflowStarted(
        event: WorkflowEvent.RunWorkflowStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        val childWorkflow = WorkflowCache.getWorkflow(
            namespace = event.config.namespace,
            name = event.config.name,
            version = event.config.version
        ) ?: throw IllegalStateException("Workflow definition not found")

        return when (event.config.sync) {
            true -> {
                val initCmd = StepByStepOrchestrator.initCmd(
                    workflowInput = event.config.input,
                    hasWaitingParent = true
                )
                val result = resume(childWorkflow, initCmd, serde, activityExecutor, cloudEventHook, lifecycleHook)
                logger.debug { "Child workflow completed" }
                when (result) {
                    is WorkflowEvent.WorkflowCompleted -> event.resumeAsCompleted(result.output)
                    is WorkflowEvent.WorkflowFailed -> event.resumeAsFailed(result.error)
                    else -> throw IllegalStateException("Child workflow returned unexpected outcome: $result")
                }
            }

            false -> {
                CoroutineScope(currentCoroutineContext()).launch {
                    val initCmd = StepByStepOrchestrator.initCmd(
                        workflowInput = event.config.input,
                        hasWaitingParent = false
                    )
                    resume(childWorkflow, initCmd, serde, activityExecutor, cloudEventHook, lifecycleHook)
                    logger.debug { "Child workflow completed" }
                }
                event.resumeAsync()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun handleForkStarted(
        workflow: Workflow,
        event: WorkflowEvent.ForkStarted,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
    ): WorkflowCommand {
        val forkNode = workflow.getNode(event.nodePosition) as Node<ForkTask>
        val branches = forkNode.branches.ifEmpty {
            throw IllegalStateException("Fork node in ${forkNode.position} has no branches")
        }

        return try {
            val output = if (forkNode.task.fork.isCompete) {
                ForkBranchExecutor.executeCompete(
                    workflow, event.nodeStack, branches, event.rawInput,
                    serde, activityExecutor, cloudEventHook, lifecycleHook, ::resume
                )
            } else {
                ForkBranchExecutor.executeCooperative(
                    workflow, event.nodeStack, branches, event.rawInput,
                    serde, activityExecutor, cloudEventHook, lifecycleHook, ::resume
                )
            }
            logger.debug { "Fork completed: output=$output" }
            WorkflowCommand.ResumeWithCompletedTask(nodeStack = event.nodeStack, rawOutput = output)
        } catch (e: InternalException) {
            logger.error { "Fork failed: error=$e" }
            WorkflowCommand.ResumeWithFailedTask(
                nodeStack = event.nodeStack,
                error = InternalException.Error.from(e, forkNode.position)
            )
        }
    }


}
