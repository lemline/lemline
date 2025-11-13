package com.lemline.core.orchestrator

import com.lemline.core.errors.ChildWorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.states.States
import io.serverlessworkflow.api.types.FlowDirective
import kotlin.time.Duration
import kotlinx.serialization.json.JsonElement

/**
 * Result types returned by PausableOrchestrator when it detects a stopping point
 * during workflow execution.
 *
 * The PausableOrchestrator executes workflows step-by-step and pauses at specific
 * boundaries (activities, delays, sub-workflows) to allow the runner to persist
 * state and resume execution in different workers.
 */
sealed class WorkflowResult {
    /**
     * Workflow execution completed successfully.
     *
     * This indicates that the entire workflow has finished executing and
     * there are no more steps to process. The runner should mark the workflow
     * instance as complete and emit the final output.
     *
     * @property output The final output dataset from the workflow
     */
    data class WorkflowCompleted(
        val output: JsonElement
    ) : WorkflowResult()

    /**
     * Represents a failure in a workflow process, encapsulating details about the states and error context.
     *
     * If rawInput is not null, this error comes from the [WorkflowOrchestrator.resumeFromTask] method
     * If rawOutput is not null, this error comes from the [WorkflowOrchestrator.resumeFromInterruptedTask] method
     *
     * @property states The state information associated with the workflow at the time of failure.
     * @property node The node in the workflow where the failure occurred.
     * @property rawInput The raw input data at the point of failure, if available.
     * @property rawOutput The raw output data at the point of failure, if available.
     * @property error The exception that caused the workflow to fail.
     */
    data class WorkflowFailed(
        val states: States,
        val node: Node<*>,
        val rawInput: JsonElement?,
        val rawOutput: JsonElement?,
        val error: Exception
    ) : WorkflowResult() {
        override fun toString() = "WorkflowFailed(" +
            "node=${node.reference}" +
            ", rawInput=$rawInput" +
            ", rawOutput=$rawOutput" +
            ", error=$error" +
            ", states=${states.map { it.key.reference + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents the state of an activity that has been completed,
     * including information about the next node position,
     * the states involved, and the generated output.
     *
     * @property states Represents a map containing workflow state.
     * @property nextNode The next node
     * @property nextRawInput The input of the next node
     * @property nextFlowDirective The flow directive to be applied to the next node
     */
    data class NextTask(
        val states: States,
        val nextNode: Node<*>,
        val nextRawInput: JsonElement,
        val nextFlowDirective: FlowDirective?
    ) : WorkflowResult() {
        override fun toString() = "NextTask(" +
            "nextNode=${nextNode.reference}" +
            ", nextRawInput=$nextRawInput" +
            ", nextFlowDirective=$nextFlowDirective" +
            ", states=${states.map { it.key.reference + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a result that indicates a wait is needed during processing.
     *
     * @property states The states after the wait.
     * @property node Specifies the wait task.
     * @property rawOutput Contains transformed input == raw output in the wait task.
     * @property duration Defines the duration for which the wait is required.
     */
    data class ExecuteWait(
        val states: States,
        val node: Node<*>,
        val rawOutput: JsonElement,
        val duration: Duration
    ) : WorkflowResult() {
        override fun toString() = "ExecuteWait(" +
            "node=${node.reference}" +
            ", rawOutput=$rawOutput" +
            ", duration=$duration" +
            ", states=${states.map { it.key.reference + "=" + it.value }}" +
            ")"
    }

    /**
     * Represents a state where a retry is required for a specific node in the system.
     *
     * @property states The states at retry.
     * @property node Specifies the task to retry.
     * @property rawInput The raw input of the task to retry.
     * @property duration The time duration to wait before retrying.
     */
    data class RetryTask(
        val states: States,
        val node: Node<*>,
        val rawInput: JsonElement,
        val flowDirective: FlowDirective?,
        val duration: Duration
    ) : WorkflowResult() {
        override fun toString() = "RetryTask(" +
            "node=${node.reference}" +
            ", rawInput=$rawInput" +
            ", flowDirective=$flowDirective" +
            ", duration=$duration" +
            ", states=${states.map { it.key.reference + "=" + it.value }}" +
            ")"
    }


    /**
     * Represents a result state where a sub-workflow needs to be initiated.
     *
     * @property states Represents a map containing the workflow state.
     * @property node Specifies the RunWorkflow node
     * @property transformedInput The transformed input of the run workflow task.
     * @property childConfig Configuration details specifying the child workflow to be started.
     */
    data class ExecuteRunWorkflow(
        val states: States,
        val node: Node<*>,
        val transformedInput: JsonElement?,
        val childConfig: ChildWorkflowException.Config,
    ) : WorkflowResult() {
        override fun toString() = "ExecuteRunWorkflow(" +
            "node=${node.reference}" +
            ", transformedInput=$transformedInput" +
            ", childConfig=$childConfig" +
            ", states=${states.map { it.key.reference + "=" + it.value }}" +
            ")"
    }
}
