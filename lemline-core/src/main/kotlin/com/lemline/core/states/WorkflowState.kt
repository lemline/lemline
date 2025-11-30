// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.AsyncTaskException.RunWorkflowStartedException
import com.lemline.core.errors.InternalException
import com.lemline.core.json.LemlineJson
import com.lemline.core.workflows.FlowDirective
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement

/**
 * Result types returned by WorkflowOrchestrator when it detects a stopping point
 * during workflow execution.
 *
 * The WorkflowOrchestrator executes workflows step-by-step and pauses at specific
 * boundaries (activities, delays, sub-workflows) to allow the runner to persist
 * state and resume execution in different workers.
 */
@Serializable
sealed class WorkflowState {
    abstract val nodeStack: NodeStack
    abstract val nodePosition: NodePosition

    open fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(jsonString: String): WorkflowState = LemlineJson.decodeFromString(jsonString)
    }

    val workflowId: WorkflowId get() = (nodeStack[NodePosition.root] as RootState).workflowId

    val hasWaitingParent: Boolean get() = (nodeStack[NodePosition.root] as RootState).hasWaitingParent

    /**
     * Simple integer counter that increments each time we enter a task.
     * Used for generating unique database IDs for outbox tables (waits, retries, parents).
     *
     * Unlike [nodePosition] which is static (e.g., "/for/do/task"), this is a simple
     * counter that ensures uniqueness across multiple visits to the same node.
     */
    val workflowStep: Int get() = (nodeStack[NodePosition.root] as RootState).workflowStep
}

@Serializable
sealed class WorkflowCommand : WorkflowState() {

    /**
     * Command to resume workflow execution from a specific task (possibly not yet in nodeStack).
     */
    @Serializable
    @SerialName("resumeFromTask")
    data class ResumeFromTask(
        override val nodeStack: NodeStack,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective? = null
    ) : WorkflowCommand() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"


        fun duplicate(workflowId: WorkflowId): ResumeFromTask {
            val rootState = nodeStack[NodePosition.root] as RootState
            return copy(
                nodeStack = nodeStack.withRootState(rootState.copy(workflowId = workflowId))
            )
        }
    }

    /**
     * Command to resume execution with a task completed asynchronously.
     */
    @Serializable
    @SerialName("resumeWithCompletedTask")
    data class ResumeWithCompletedTask(
        override val nodeStack: NodeStack,
        val rawOutput: JsonElement,
    ) : WorkflowCommand() {

        @Transient
        override val nodePosition = nodeStack.lastPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Command to resume execution with a task failed asynchronously.
     */
    @Serializable
    @SerialName("resumeWithFailedTask")
    data class ResumeWithFailedTask(
        override val nodeStack: NodeStack,
        val error: InternalException.Error,
    ) : WorkflowCommand() {

        @Transient
        override val nodePosition = nodeStack.lastPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", error=$error" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }
}

@Serializable
sealed class WorkflowEvent : WorkflowState() {

    /**
     * Represents the result of a workflow's progression.
     */
    sealed class Outcome : WorkflowEvent() {
        /**
         * Extracts the associated value from the [WorkflowEvent.Outcome] based on its type.
         */

        internal fun value(): JsonElement = when (this) {
            is WorkflowCompleted -> output
            is ForkBranchCompleted -> output
            is WorkflowFailed -> throw exception
            is ForkBranchFailed -> throw exception
        }
    }

    /**
     * Event emitted when a workflow completes.
     */
    @Serializable
    @SerialName("workflowCompleted")
    data class WorkflowCompleted(
        val output: JsonElement,
        val completedAt: Instant,
        override val nodeStack: NodeStack,
    ) : Outcome() {

        @Transient
        override val nodePosition = NodePosition.root

        override fun toString() = "${this::class.simpleName}(" +
            "output=$output" +
            ")"
    }

    /**
     * Event emitted when a task fails without the error being caught (outside a fork).
     *
     * If rawInput is not null, this error comes from the [com.lemline.core.orchestrator.StepByStepOrchestrator.resumeFromTask] method
     * If rawOutput is not null, this error comes from the [com.lemline.core.orchestrator.StepByStepOrchestrator.resumeFromCompletedTask] method
     */
    @Serializable
    @SerialName("workflowFailed")
    data class WorkflowFailed(
        override val nodeStack: NodeStack,
        val rawInput: JsonElement?,
        val rawOutput: JsonElement?,
        val flowDirective: FlowDirective?,
        val error: InternalException.Error,
        val failedAt: Instant,
    ) : Outcome() {

        @Transient
        override val nodePosition = nodeStack.lastPosition

        val exception: Exception get() = InternalException(error)

        constructor(
            nodeStack: NodeStack,
            nodePosition: NodePosition,
            rawInput: JsonElement?,
            rawOutput: JsonElement?,
            flowDirective: FlowDirective?,
            exception: Exception,
            failedAt: Instant = Clock.System.now(),
        ) : this(
            nodeStack = nodeStack,
            rawInput = rawInput,
            rawOutput = rawOutput,
            flowDirective = flowDirective,
            error = InternalException.Error.from(exception, nodePosition),
            failedAt = failedAt
        )

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", rawOutput=$rawOutput" +
            ", flowDirective=$flowDirective" +
            ", error=$error" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a fork branch execution completes.
     */
    @Serializable
    @SerialName("forkBranchCompleted")
    data class ForkBranchCompleted(
        override val nodeStack: NodeStack,
        val branchName: String,
        val output: JsonElement,
        val completedAt: Instant,
        val flowDirective: FlowDirective?
    ) : Outcome() {

        @Transient
        override val nodePosition = nodeStack.lastPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", transformedInput=$output" +
            ", flowDirective=$flowDirective" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a fork branch execution fails.
     */
    @Serializable
    @SerialName("forkBranchFailed")
    data class ForkBranchFailed(
        override val nodeStack: NodeStack,
        val branchName: String,
        val error: InternalException.Error,
        val failedAt: Instant
    ) : Outcome() {
        val exception: InternalException by lazy { InternalException(error) }

        @Transient
        override val nodePosition = nodeStack.lastPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", error=$error" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    sealed class Suspension : WorkflowEvent()

    /**
     * Event emitted when the next task (possibly not yet in nodeStack) is scheduled.
     */
    @Serializable
    @SerialName("taskScheduled")
    data class TaskScheduled(
        override val nodeStack: NodeStack,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?
    ) : Suspension() {

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeFromTask(
            nodeStack = nodeStack,
            nodePosition = nodePosition,
            rawInput = rawInput,
            flowDirective = flowDirective,
        )
    }

    /**
     * Event emitted when a wait task is scheduled.
     */
    @Serializable
    @SerialName("waitStarted")
    data class WaitStarted(
        override val nodeStack: NodeStack,
        val waitState: WaitState,
        val rawOutput: JsonElement,
        @Contextual val waitUntil: Instant
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.lastPosition // Wait position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", waitUntil=$waitUntil" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )
    }

    /**
     * Event emitted when the retry of a task (possibly not yet in nodeStack) is scheduled.
     */
    @Serializable
    @SerialName("taskRetryScheduled")
    data class TaskRetryScheduled(
        override val nodeStack: NodeStack,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?,
        val retryAt: Instant
    ) : Suspension() {

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", retryAt=$retryAt" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeFromTask(
            nodeStack = nodeStack,
            nodePosition = nodePosition,
            rawInput = rawInput,
            flowDirective = flowDirective,
        )
    }


    /**
     * Event emitted when a child workflow is scheduled.
     */
    @Serializable
    @SerialName("runWorkflowStarted")
    data class RunWorkflowStarted(
        override val nodeStack: NodeStack,
        val runState: RunState,
        val rawInput: JsonElement,
        val childConfig: RunWorkflowStartedException.Config,
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.lastPosition // RunWorkflow position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", runState=$runState" +
            ", transformedInput=$rawInput" +
            ", childConfig=$childConfig" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resumeAsCompleted(rawOutput: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )

        fun resumeAsFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
            nodeStack = nodeStack,
            error = error,
        )

        fun resumeAsync() = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawInput,
        )
    }

    /**
     * Event emitted when a fork is started.
     * (each branch is processed separately)
     */
    @Serializable
    @SerialName("forkStarted")
    data class ForkStarted(
        override val nodeStack: NodeStack,
        val forkState: ForkState,
        val rawInput: JsonElement,
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.lastPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", forkState=$forkState" +
            ", rawInput=$rawInput" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume(rawOutput: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )
    }

    /**
     * Event emitted when an emit task publishes a CloudEvent.
     *
     * The CloudEvent is built using the official CloudEvents SDK (io.cloudevents)
     * for proper CloudEvents v1.0 compliance. This event is handled inline by
     * WorkflowCommandHandler and never flows through broker messages, so the
     * CloudEvent doesn't need to be serializable.
     *
     * The workflow continues immediately after publishing (fire-and-forget).
     */
    @Serializable
    @SerialName("emitStarted")
    data class EmitStarted(
        override val nodeStack: NodeStack,
        val emitState: EmitState,
        @Transient val cloudEvent: io.cloudevents.CloudEvent? = null,  // Not serialized - handled inline
        val rawOutput: JsonElement   // Pass-through: output = input for emit task
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.lastPosition // Emit position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", cloudEvent=${cloudEvent?.let { "id=${it.id}, source=${it.source}, type=${it.type}" }}" +
            ", rawOutput=$rawOutput" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )
    }
}
