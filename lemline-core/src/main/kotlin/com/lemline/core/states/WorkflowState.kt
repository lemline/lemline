// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.FlowDirective
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
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
    abstract val taskStates: TaskStates
    abstract val nodePosition: NodePosition

    fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(jsonString: String): WorkflowState = LemlineJson.decodeFromString(jsonString)
    }

    /**
     * Represents the initial state of a workflow, capturing the start time and input data.
     *
     * @property startedAt The timestamp representing when the state began.
     * @property input The JSON input data associated with the state.
     */
    @ExperimentalTime
    @Serializable
    data class Starting(
        val startedAt: Instant,
        val input: JsonElement,
    ) : WorkflowState() {
        override val taskStates: TaskStates = mutableMapOf()
        override val nodePosition: NodePosition = NodePosition.root

        override fun toString() = "Starting(" +
            "startedAt=$startedAt" +
            ", input=$input" +
            ")"
    }

    /**
     * Workflow execution completed successfully.
     *
     * This indicates that the entire workflow has finished executing and
     * there are no more steps to process. The runner should mark the workflow
     * instance as complete and emit the final output.
     *
     * @property output The final output dataset from the workflow
     */
    @Serializable
    data class Completed(
        val output: JsonElement
    ) : WorkflowState() {
        override val taskStates: TaskStates = emptyMap()
        override val nodePosition: NodePosition = NodePosition.root

        override fun toString() = "Completed(" +
            "output=$output" +
            ")"
    }

    /**
     * Represents a failure in a workflow process, encapsulating details about the states and error context.
     *
     * If rawInput is not null, this error comes from the [com.lemline.core.orchestrator.WorkflowOrchestrator.resumeFromTask] method
     * If rawOutput is not null, this error comes from the [com.lemline.core.orchestrator.WorkflowOrchestrator.resumeFromInterruptedTask] method
     *
     * @property taskStates The state information associated with the workflow at the time of failure.
     * @property nodePosition The position of the node in the workflow where the failure occurred.
     * @property rawInput The raw input data at the point of failure, if available.
     * @property rawOutput The raw output data at the point of failure, if available.
     * @property exception The exception that caused the workflow to fail.
     * @property error The serialized exception that caused the workflow to fail.
     */
    @Serializable
    data class Failed(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement?,
        val rawOutput: JsonElement?,
        val flowDirective: FlowDirective?,
        val error: InternalWorkflowException.Error,
        @Transient val exception: Exception = InternalWorkflowException(error)
    ) : WorkflowState() {

        constructor(
            taskStates: TaskStates,
            nodePosition: NodePosition,
            rawInput: JsonElement?,
            rawOutput: JsonElement?,
            flowDirective: FlowDirective?,
            exception: Exception
        ) : this(
            taskStates = taskStates,
            nodePosition = nodePosition,
            rawInput = rawInput,
            rawOutput = rawOutput,
            flowDirective = flowDirective,
            error = InternalWorkflowException.Error.fromUnexpectedException(exception, nodePosition),
            exception = exception
        )

        override fun toString() = "Failed(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", rawOutput=$rawOutput" +
            ", flowDirective=$flowDirective" +
            ", error=$error" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents the state of an activity that has been completed,
     * including information about the next node position,
     * the states involved, and the generated output.
     *
     * @property taskStates Represents a map containing workflow state.
     * @property nodePosition The position of the next node
     * @property rawInput The input of the next node
     * @property flowDirective The flow directive to be applied to the next node
     */
    @Serializable
    data class ReadyForNextTask(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?
    ) : WorkflowState() {
        override fun toString() = "ReadyForNextTask(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a result that indicates a wait is needed during processing.
     *
     * @property taskStates The states after the wait.
     * @property nodePosition Specifies the position of the wait task.
     * @property rawOutput Contains transformed input == raw output in the wait task.
     * @property waitUntil Defines the duration for which the wait is required.
     */
    @Serializable
    @ExperimentalTime
    data class Waiting(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawOutput: JsonElement,
        @Contextual val waitUntil: Instant
    ) : WorkflowState() {
        override fun toString() = "Waiting(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", waitUntil=$waitUntil" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a state where a retry is required for a specific node in the system.
     *
     * @property taskStates The states at retry.
     * @property nodePosition Specifies the position of the task to retry.
     * @property rawInput The raw input of the task to retry.
     * @property retryAt The time duration to wait before retrying.
     */
    @Serializable
    @ExperimentalTime
    data class Retrying(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?,
        val retryAt: Instant
    ) : WorkflowState() {
        override fun toString() = "WaitingToRetry(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", retryAt=$retryAt" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }


    /**
     * Represents a result state where a sub-workflow needs to be initiated.
     *
     * @property taskStates Represents a map containing the workflow state.
     * @property nodePosition Specifies the position of the RunWorkflow node
     * @property rawOutput The transformed input of the run workflow task.
     * @property childConfig Configuration details specifying the child workflow to be started.
     */
    @Serializable
    data class RunningChildWorkflow(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawOutput: JsonElement?,
        val childConfig: ChildWorkflowException.Config,
    ) : WorkflowState() {
        override fun toString() = "RunningChildWorkflow(" +
            "nodePosition=$nodePosition" +
            ", transformedInput=$rawOutput" +
            ", childConfig=$childConfig" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a workflow paused during fork execution.
     *
     * This state is returned when ExecutionMode.Async encounters a fork task.
     * The runner is responsible for:
     * - Scheduling branch executions (potentially in parallel)
     * - Tracking branch completion
     * - Resuming the fork task when appropriate
     *
     * Similar to RunningChildWorkflow pattern.
     *
     * Fork configuration (compete mode, branches) is derived from the Node<ForkTask>
     * at nodePosition. Only runtime completion tracking is stored here.
     *
     * @property taskStates Current workflow state
     * @property nodePosition Position of the fork task node
     * @property rawInput Input to pass to all branches
     * @property completedBranches Map of branch index to output (tracks which branches completed and their results)
     */
    @Serializable
    data class RunningFork(
        override val taskStates: TaskStates,
        override val nodePosition: NodePosition,
        val rawInput: JsonElement,
        @Transient
        val completedBranches: Map<Int, JsonElement> = emptyMap()
    ) : WorkflowState() {
        override fun toString() = "RunningFork(" +
            "nodePosition=$nodePosition" +
            ", completed=${completedBranches.size}" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }
}

/**
 * Status of a branch execution.
 * Used by the runner module for database tracking.
 */
@Serializable
enum class BranchStatus {
    PENDING,    // Not yet started
    RUNNING,    // Currently executing
    COMPLETED,  // Successfully finished
    FAULTED     // Failed with error
}
