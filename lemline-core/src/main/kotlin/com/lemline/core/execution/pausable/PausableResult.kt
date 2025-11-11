package com.lemline.core.execution.pausable

import com.lemline.core.errors.ChildWorkflowConfig
import com.lemline.core.states.States
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
sealed class PausableResult {
    /**
     * Workflow execution completed successfully.
     *
     * This indicates that the entire workflow has finished executing and
     * there are no more steps to process. The runner should mark the workflow
     * instance as complete and emit the final output.
     *
     * @property output The final output dataset from the workflow
     */
    data class WorkflowCompleted(val output: JsonElement) : PausableResult()

    /**
     * Represents the state of an activity that has been completed,
     * including information about the next node position,
     * the states involved, and the generated output.
     *
     * @property nextNodePosition Specifies the position of the next node
     * @property states Represents a map containing workflow state.
     * @property output A JSON element encapsulating the task output.
     */
    data class ActivityCompleted(
        val nextNodePosition: String,
        val states: States,
        val output: JsonElement
    ) : PausableResult()

    /**
     * Represents a result that indicates a wait is needed during processing.
     *
     * @property states The states after the wait.
     * @property nextNodePosition Specifies the position of the next node after the wait.
     * @property nextInput Contains the output data or context generated before the wait state is triggered.
     * @property duration Defines the duration for which the wait is required.
     */
    data class WaitNeeded(
        val states: States,
        val nextNodePosition: String,
        val nextInput: JsonElement,
        val duration: Duration
    ) : PausableResult()

    /**
     * Represents a state where a retry is required for a specific node in the system.
     *
     * @property states The states at retry.
     * @property nodePosition The identifier of the node associated with the retry situation.
     * @property input The input data causing the retry condition.
     * @property duration The time duration to wait before retrying.
     */
    data class RetryNeeded(
        val states: States,
        val nodePosition: String,
        val input: JsonElement,
        val duration: Duration
    ) : PausableResult()


    /**
     * Represents a result state where a sub-workflow needs to be initiated.
     *
     * @property states Represents a map containing the workflow state.
     * @property nodePosition The position of the node where the sub-workflow is required.
     * @property output the raw output of the node where the sub-workflow is triggered. (non-null if async)
     * @property childConfig Configuration details specifying the child workflow to be started.
     */
    data class SubWorkflowNeeded(
        val states: States,
        val nodePosition: String,
        val output: JsonElement?,
        val childConfig: ChildWorkflowConfig,
    ) : PausableResult()
}
