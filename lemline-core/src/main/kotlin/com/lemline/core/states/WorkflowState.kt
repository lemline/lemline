// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.InternalException
import com.lemline.core.json.LemlineJson
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.processors.RunWorkflowConfig
import com.lemline.core.processors.WaitConfig
import com.lemline.core.tasks.FlowDirective
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
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

    val workflowId: WorkflowId get() = nodeStack.rootState.workflowId

    val hasWaitingParent: Boolean get() = nodeStack.rootState.hasWaitingParent

    val isNew: Boolean get() = nodeStack[nodePosition] == null
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
            return copy(nodeStack = nodeStack.duplicate(workflowId = workflowId))
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
        override val nodePosition = nodeStack.currentPosition

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
        override val nodePosition = nodeStack.currentPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", error=$error" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }
}

@Serializable
sealed class WorkflowEvent : WorkflowState() {


    // ========================================
    // Outcome Events
    // ========================================

    /**
     * Represents the result of a workflow's progression.
     */
    sealed class Outcome : WorkflowEvent() {
        /**
         * Extracts the associated value from the [WorkflowEvent.Outcome] based on its type.
         */

        fun value(): JsonElement = when (this) {
            is WorkflowCompleted -> output
            is ForkBranchCompleted -> output
            is ForEachCompleted -> output
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
        override val nodePosition = nodeStack.currentPosition

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
    ) : Outcome() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", transformedInput=$output" +
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
        override val nodePosition = nodeStack.currentPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", branchName=$branchName" +
            ", error=$error" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    // ========================================
    // Activity Events (execute and continue)
    // ========================================

    /**
     * Base class for events that represent activities to be executed.
     *
     * Unlike Suspension events which require async coordination (timers, child workflows,
     * parallel branches), ActivityStarted events represent work that can be executed
     * immediately and resumed with the result.
     *
     * The orchestrator executes the activity via an ActivityExecutor and resumes
     * with the output (or error).
     */
    sealed class ActivityStarted : WorkflowEvent() {
        /** Pass-through output for resume (usually equals transformedInput) */
        abstract val input: JsonElement

        fun resumeCompleted(output: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = output,
        )

        fun resumeFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
            nodeStack = nodeStack,
            error = error,
        )
    }

    // ========================================
    // Suspension Events (require async coordination)
    // ========================================

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
        val rawOutput: JsonElement,
        @Contextual val config: WaitConfig
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // Wait position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", config=$config" +
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
        val rawInput: JsonElement,
        val config: RunWorkflowConfig,
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // RunWorkflow position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", transformedInput=$rawInput" +
            ", childConfig=$config" +
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
        val rawInput: JsonElement,
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // Fork position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawInput=$rawInput" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resume(rawOutput: JsonElement) = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = rawOutput,
        )
    }

    /**
     * Event emitted when a listen task is waiting for CloudEvents.
     *
     * The config contains all data needed to set up an event listener.
     * The runner creates a listener row in the database and waits for
     * matching events to arrive via the CloudEvent channel.
     *
     * The workflow is resumed when:
     * - Strategy ONE: First matching event arrives
     * - Strategy ANY: First matching event (or until condition met if accumulating)
     * - Strategy ALL: One event per filter has been received
     * - Timeout: If timeoutAt is set and exceeded
     */
    @Serializable
    @SerialName("listenStarted")
    data class ListenStarted(
        override val nodeStack: NodeStack,
        val rawOutput: JsonElement,
        val config: ListenConfig
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // Listen position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", rawOutput=$rawOutput" +
            ", config=$config" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        fun resumeCompleted(events: JsonArray) = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = events,
        )

        fun resumeFailed(error: InternalException.Error) = WorkflowCommand.ResumeWithFailedTask(
            nodeStack = nodeStack,
            error = error,
        )

    }

    /**
     * Event emitted when a foreach iteration of a listen task completes successfully.
     *
     * This is an Outcome because it represents a terminal state for the foreach iteration,
     * similar to how ForkBranchCompleted represents a terminal state for a fork branch.
     * The FullOrchestrator handles this by extracting the iteration output and continuing
     * to process subsequent events.
     *
     * @see ListenStarted for the initial listen task event
     */
    @Serializable
    @SerialName("forEachCompleted")
    data class ForEachCompleted(
        override val nodeStack: NodeStack,
        val output: JsonElement,
    ) : Outcome() {

        override val nodePosition by lazy { nodeStack.currentPosition }

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", output=$output" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when an emit task needs to publish a CloudEvent.
     *
     * The config contains all data needed to build a CloudEvent. The actual
     * CloudEvent is built by the orchestrator using the CloudEvents SDK and
     * emitted via [com.lemline.core.cloudevents.CloudEventHook].
     *
     * The workflow continues immediately after publishing (fire-and-forget).
     */
    @Serializable
    @SerialName("emitStarted")
    data class EmitStarted(
        override val nodeStack: NodeStack,
        val input: JsonElement,   // Pass-through: output = input for emit task
        val config: EmitConfig
    ) : Suspension() {

        @Transient
        override val nodePosition = nodeStack.currentPosition // Emit position

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", config=$config" +
            ", rawOutput=$input" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"

        /**
         * Resume the workflow after emitting the CloudEvent.
         * Output equals input (fire-and-forget semantic).
         */
        fun resume() = WorkflowCommand.ResumeWithCompletedTask(
            nodeStack = nodeStack,
            rawOutput = input,
        )
    }

    /**
     * Event emitted when an HTTP call task needs to be executed.
     *
     * The config contains all data needed to make the HTTP request,
     * including resolved authentication.
     */
    @Serializable
    @SerialName("callHttpStarted")
    data class CallHttpStarted(
        override val nodeStack: NodeStack,
        override val input: JsonElement,
        val config: CallHttpConfig
    ) : ActivityStarted() {

        @Transient
        override val nodePosition = nodeStack.currentPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", config=$config" +
            ", input=$input" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a script task needs to be executed.
     *
     * The config contains all data needed to run the script,
     * including resolved code, arguments, and environment.
     */
    @Serializable
    @SerialName("runScriptStarted")
    data class RunScriptStarted(
        override val nodeStack: NodeStack,
        override val input: JsonElement,
        val config: RunScriptConfig
    ) : ActivityStarted() {

        @Transient
        override val nodePosition = nodeStack.currentPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", config=$config" +
            ", input=$input" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }

    /**
     * Event emitted when a shell command task needs to be executed.
     *
     * The config contains all data needed to run the shell command,
     * including resolved command, arguments, and environment.
     */
    @Serializable
    @SerialName("runShellStarted")
    data class RunShellStarted(
        override val nodeStack: NodeStack,
        override val input: JsonElement,
        val config: RunShellConfig
    ) : ActivityStarted() {

        @Transient
        override val nodePosition = nodeStack.currentPosition

        override fun toString() = "${this::class.simpleName}(" +
            "nodePosition=$nodePosition" +
            ", config=$config" +
            ", input=$input" +
            ", stack=${nodeStack.map { it.key.toString() + "=" + it.value }}" +
            ")"
    }
}
