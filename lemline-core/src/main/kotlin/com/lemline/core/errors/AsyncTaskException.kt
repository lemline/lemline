// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.EmitState
import com.lemline.core.states.ForkState
import com.lemline.core.states.NodeState
import com.lemline.core.states.RunState
import com.lemline.core.states.WaitState
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

sealed class AsyncTaskException : RuntimeException() {

    abstract val state: NodeState

    /**
     * Exception indicating that a child workflow should be started.
     *
     * This exception serves as a marker to indicate the initiation of a child workflow
     * during a larger workflow process. It carries configuration details associated
     * with the child workflow, encapsulated within the [Config] instance.
     */
    data class RunWorkflowStartedException(
        override val state: RunState,
        val transformedInput: JsonElement,
        val config: Config
    ) : AsyncTaskException() {

        /**
         * Configuration details required to initiate a child workflow.
         *
         * This data class encapsulates the metadata and input parameters needed to
         * start a child workflow instance within a larger workflow process.
         *
         * @property namespace The namespace of the child workflow, used to scope workflows within an environment.
         * @property name The name of the child workflow to be executed.
         * @property version The version of the child workflow.
         * @property input The input provided to the child workflow.
         * @property sync Indicates whether the parent workflow should wait for the child workflow to complete.
         */
        @Serializable
        data class Config(
            val namespace: WorkflowNamespace,
            val name: WorkflowName,
            val version: WorkflowVersion,
            val input: JsonElement,
            val sync: Boolean
        )
    }


    /**
     * Exception indicating that a wait has been requested during a workflow's execution.
     *
     * This exception is thrown to signal that a delay or pause, defined by a [Config],
     * has been incorporated into the workflow's control flow. It can be used to coordinate
     * asynchronous operations or introduce timed pauses in workflow processing.
     *
     * @property config The configuration specifying the details of the wait, including the delay duration.
     */
    @ExperimentalTime
    data class WaitStartedException(
        override val state: WaitState,
        val transformedInput: JsonElement,
        val config: Config
    ) : AsyncTaskException() {

        /**
         * Configuration for specifying the wait duration in a workflow task.
         *
         * This configuration is used to indicate a target timestamp until which
         * a delay or pause is requested during a workflow's execution. It is typically
         * used in conjunction with orchestrators to manage timed pauses or schedule
         * task execution at a specific time.
         *
         * @property waitUntil The timestamp indicating when the wait should end.
         */
        @Serializable
        data class Config(
            @Contextual val waitUntil: Instant
        )
    }

    /**
     * Exception indicating that fork branches should be executed.
     *
     * This exception is thrown when a fork task needs to execute its branches.
     * The runner catches this and schedules branches for parallel execution.
     *
     * All fork configuration (compete mode, branches) is derived from the Node<ForkTask>
     * that threw this exception. Only the transformedInput needs to be carried.
     */
    @ExperimentalTime
    data class ForkStartedException(
        override val state: ForkState,
        val transformedInput: JsonElement
    ) : AsyncTaskException()

    /**
     * Exception indicating that a CloudEvent should be emitted.
     *
     * This exception is thrown when an emit task needs to publish a CloudEvent
     * to an external messaging system. The runner catches this, publishes the
     * CloudEvent to the cloudevents channel, and immediately resumes the workflow.
     *
     * The CloudEvent is built using the official CloudEvents SDK (io.cloudevents)
     * for proper CloudEvents v1.0 compliance.
     *
     * @property state The emit task state
     * @property transformedInput The input that will be passed through as output
     * @property cloudEvent The CloudEvent built with the official SDK
     */
    @ExperimentalTime
    data class EmitStartedException(
        override val state: EmitState,
        val transformedInput: JsonElement,
        val cloudEvent: io.cloudevents.CloudEvent
    ) : AsyncTaskException()
}
