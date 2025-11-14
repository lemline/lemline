package com.lemline.core.states

import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.NodePosition
import com.lemline.core.workflows.FlowDirective
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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

    fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(jsonString: String): WorkflowState = LemlineJson.decodeFromString(jsonString)
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

        override fun toString() = "Completed(" +
            "output=$output" +
            ")"
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

        override fun toString() = "Starting(" +
            "startedAt=$startedAt" +
            ", input=$input" +
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
     */
    @Serializable
    data class Failed(
        override val taskStates: TaskStates,
        val nodePosition: NodePosition,
        val rawInput: JsonElement?,
        val rawOutput: JsonElement?,
        val flowDirective: FlowDirective?,
        @Transient val exception: Exception? = null
    ) : WorkflowState() {

        // This is used to debug only
        val stackTrace: String? = exception?.stackTraceToString()

        override fun toString() = "Failed(" +
            "node=$nodePosition" +
            ", rawInput=$rawInput" +
            ", rawOutput=$rawOutput" +
            ", flowDirective=$flowDirective" +
            ", exception=$exception" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents the state of an activity that has been completed,
     * including information about the next node position,
     * the states involved, and the generated output.
     *
     * @property taskStates Represents a map containing workflow state.
     * @property nextNodePosition The position of the next node
     * @property nextRawInput The input of the next node
     * @property nextFlowDirective The flow directive to be applied to the next node
     */
    @Serializable
    data class ReadyForNextTask(
        override val taskStates: TaskStates,
        val nextNodePosition: NodePosition,
        val nextRawInput: JsonElement,
        val nextFlowDirective: FlowDirective?
    ) : WorkflowState() {
        override fun toString() = "ReadyForNextTask(" +
            "nextNode=$nextNodePosition" +
            ", nextRawInput=$nextRawInput" +
            ", nextFlowDirective=$nextFlowDirective" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a result that indicates a wait is needed during processing.
     *
     * @property taskStates The states after the wait.
     * @property nodePosition Specifies the position of the wait task.
     * @property rawOutput Contains transformed input == raw output in the wait task.
     * @property duration Defines the duration for which the wait is required.
     */
    @Serializable
    data class Waiting(
        override val taskStates: TaskStates,
        val nodePosition: NodePosition,
        val rawOutput: JsonElement,
        val duration: Duration
    ) : WorkflowState() {
        override fun toString() = "Waiting(" +
            "node=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", duration=$duration" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a state where a retry is required for a specific node in the system.
     *
     * @property taskStates The states at retry.
     * @property nodePosition Specifies the position of the task to retry.
     * @property rawInput The raw input of the task to retry.
     * @property duration The time duration to wait before retrying.
     */
    @Serializable
    data class WaitingToRetry(
        override val taskStates: TaskStates,
        val nodePosition: NodePosition,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?,
        val duration: Duration
    ) : WorkflowState() {
        override fun toString() = "WaitingToRetry(" +
            "node=$nodePosition" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", duration=$duration" +
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
        val nodePosition: NodePosition,
        val rawOutput: JsonElement?,
        val childConfig: ChildWorkflowException.Config,
    ) : WorkflowState() {
        override fun toString() = "RunningChildWorkflow(" +
            "node=$nodePosition" +
            ", transformedInput=$rawOutput" +
            ", childConfig=$childConfig" +
            ", states=${taskStates.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }
}
