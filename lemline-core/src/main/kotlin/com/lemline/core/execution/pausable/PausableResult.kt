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
        val nextNodePosition: String?,
        val states: States,
        val output: JsonElement
    ) : PausableResult()

    /**
     * Represents a result that indicates a wait is required before proceeding.
     *
     * @property nextNodePosition Specifies the position of the next node
     * @property states Represents a map containing the workflow state.
     * @property duration The duration for which the wait is required.
     */
    data class WaitNeeded(
        val nextNodePosition: String?,
        val states: States,
        val duration: Duration
    ) : PausableResult()

    /**
     * Represents a result that indicates a retry operation is needed.
     *
     * @property nodePosition The position of the node where the retry is required.
     * @property states Represents a map containing the workflow state.
     * @property duration The duration period after which a retry might be attempted.
     */
    data class RetryNeeded(
        val nodePosition: String,
        val states: States,
        val duration: Duration
    ) : PausableResult()


    /**
     * Represents a result state where a sub-workflow needs to be initiated.
     *
     * @property nodePosition The position of the node where the sub-workflow is required.
     * @property states Represents a map containing the workflow state.
     * @property childConfig Configuration details specifying the child workflow to be started.
     */
    data class SubWorkflowNeeded(
        val nodePosition: String,
        val states: States,
        val childConfig: ChildWorkflowConfig,
    ) : PausableResult()
}
