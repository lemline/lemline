// SPDX-License-Identifier: BUSL-1.1
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
import kotlinx.serialization.Serializable
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
    abstract val taskStates: TaskStates
    abstract val nodePosition: NodePosition

    open fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(jsonString: String): WorkflowState = LemlineJson.decodeFromString(jsonString)
    }

    val workflowId: WorkflowId get() = (taskStates[NodePosition.root] as RootState).workflowId

    val hasWaitingParent: Boolean get() = (taskStates[NodePosition.root] as RootState).hasWaitingParent

    /**
     * Simple integer counter that increments each time we enter a task.
     * Used for generating unique database IDs for outbox tables (waits, retries, parents).
     *
     * Unlike [nodePosition] which is static (e.g., "/for/do/task"), this is a simple
     * counter that ensures uniqueness across multiple visits to the same node.
     */
    val workflowStep: Int get() = (taskStates[NodePosition.root] as RootState).workflowStep
}

@Serializable
sealed class WorkflowCommand : WorkflowState() {

    /**
     * Command to resume workflow execution from a specific task.
     */
    @Serializable
    data class ResumeFromTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective? = null
    ) : WorkflowCommand() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        @ExperimentalTime
        fun duplicate(workflowId: WorkflowId): ResumeFromTask {
            val rootState = taskStates[NodePosition.root] as RootState
            return copy(
                taskStates = taskStates + mapOf(NodePosition.root to rootState.copy(workflowId = workflowId))
            )
        }
    }

    /**
     * Command to resume execution with a task completed asynchronously.
     */
    @Serializable
    data class ResumeWithCompletedTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawOutput: JsonElement,
    ) : WorkflowCommand() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Command to resume execution with a task failed asynchronously.
     */
    @Serializable
    data class ResumeWithFailedTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val error: InternalException.Error,
    ) : WorkflowCommand() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", error=$error" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
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
        @ExperimentalTime
        internal fun value(): JsonElement = when (this) {
            is WorkflowCompleted -> output
            is BranchCompleted -> output
            is WorkflowFailed -> throw exception
            is BranchFailed -> throw exception
        }
    }

    /**
     * Event emitted when a workflow completes.
     */
    @ExperimentalTime
    @Serializable
    data class WorkflowCompleted(
        val output: JsonElement,
        val completedAt: Instant,
        override val taskStates: TaskStates,
    ) : Outcome() {
        override val nodePosition: NodePosition = NodePosition.root

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
    @ExperimentalTime
    @Serializable
    data class WorkflowFailed(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement?,
        val rawOutput: JsonElement?,
        val flowDirective: FlowDirective?,
        val error: InternalException.Error,
        val failedAt: Instant,
    ) : Outcome() {

        val exception: Exception get() = InternalException(error)

        constructor(
            taskStates: TaskStates,
            nodePosition: NodePosition,
            rawInput: JsonElement?,
            rawOutput: JsonElement?,
            flowDirective: FlowDirective?,
            exception: Exception,
            failedAt: Instant = Clock.System.now(),
        ) : this(
            taskStates = taskStates,
            nodePosition = nodePosition,
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
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a fork branch execution completes.
     */
    @ExperimentalTime
    @Serializable
    data class BranchCompleted(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val branchName: String,
        val output: JsonElement,
        val completedAt: Instant,
        val flowDirective: FlowDirective?
    ) : Outcome() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", transformedInput=$output" +
            ", flowDirective=$flowDirective" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a fork branch execution fails.
     */
    @ExperimentalTime
    @Serializable
    data class BranchFailed(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val branchName: String,
        val error: InternalException.Error,
        val failedAt: Instant
    ) : Outcome() {
        val exception: InternalException
            get() = InternalException(error)

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", error=$error" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    sealed class Suspension : WorkflowEvent()

    /**
     * Event emitted when the next task is scheduled.
     */
    @Serializable
    data class TaskScheduled(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?
    ) : Suspension() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeFromTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawInput = rawInput,
            flowDirective = flowDirective,
        )
    }

    /**
     * Event emitted when a wait task is scheduled.
     */
    @Serializable
    @ExperimentalTime
    data class WaitStarted(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val waitState: WaitState,
        val rawOutput: JsonElement,
        @Contextual val waitUntil: Instant
    ) : Suspension() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", waitUntil=$waitUntil" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeWithCompletedTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawOutput = rawOutput,
        )
    }

    /**
     * Event emitted when a task retry is scheduled.
     */
    @Serializable
    @ExperimentalTime
    data class RetryScheduled(
        override val taskStates: TaskStates,
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
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume() = WorkflowCommand.ResumeFromTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawInput = rawInput,
            flowDirective = flowDirective,
        )
    }


    /**
     * Event emitted when a child workflow is scheduled.
     */
    @Serializable
    data class RunWorkflowStarted(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val runState: RunState,
        val rawInput: JsonElement,
        val childConfig: RunWorkflowStartedException.Config,
    ) : Suspension() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", runState=$runState" +
            ", transformedInput=$rawInput" +
            ", childConfig=$childConfig" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resumeAsCompleted(rawOutput: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawOutput = rawOutput,
        )

        fun resumeAsFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            error = error,
        )

        fun resumeAsync() = WorkflowCommand.ResumeWithCompletedTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawOutput = rawInput,
        )
    }

    /**
     * Event emitted when a fork is started.
     * (each branch is processed separately)
     */
    @Serializable
    data class ForkStarted(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val forkState: ForkState,
        val rawInput: JsonElement,
    ) : Suspension() {
        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", forkState=$forkState" +
            ", rawInput=$rawInput" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume(rawOutput: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawOutput = rawOutput,
        )
    }
}
