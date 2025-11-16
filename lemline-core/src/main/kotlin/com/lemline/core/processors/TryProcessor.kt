// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.errors.InternalWorkflowException
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodePosition
import com.lemline.core.orchestrator.StepResult
import com.lemline.core.orchestrator.WorkflowOrchestrator
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.states.TaskState
import com.lemline.core.states.TryTaskState
import com.lemline.core.utils.toDuration
import com.lemline.core.utils.toRandomDuration
import io.serverlessworkflow.api.types.ConstantBackoff
import io.serverlessworkflow.api.types.ErrorFilter
import io.serverlessworkflow.api.types.ExponentialBackOff
import io.serverlessworkflow.api.types.LinearBackoff
import io.serverlessworkflow.api.types.RetryPolicy
import io.serverlessworkflow.api.types.TryTask
import io.serverlessworkflow.api.types.TryTaskCatch
import kotlin.math.pow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Node processor for TryTask (error handling) - pure functional model.
 *
 * TryTask attempts execution of a try block with optional retry and catch blocks
 * for error handling. This is the only flow task that stores `transformedInput`
 * to provide consistent input for retries and catch blocks.
 *
 * ## Structure
 *
 * The TryTask has children that represent the try body (a DoTask). The catch
 * block is defined in the task definition itself (node.task.catch).
 *
 * ## Example Workflow
 *
 * ```yaml
 * - processOrder:
 *     try:
 *       - callAPI:
 *           call: http
 *           with:
 *             url: https://api.example.com/process
 *     catch:
 *       retry:
 *         limit:
 *           attempt:
 *             count: 3
 *         delay: PT1S
 *       errors:
 *         with:
 *           type: https://serverlessworkflow.io/spec/1.0.0/errors/communication
 *       do:
 *         - logError:
 *             set:
 *               errorLogged: true
 * ```
 *
 * @property node Immutable TryTask definition
 */
class TryProcessor(
    node: Node<TryTask>
) : NodeProcessor<TryTask, TryTaskState>(node) {

    /**
     * This state is initialized when entering the TryTask node for the first time
     */
    override fun createState(transformedInput: JsonElement, scope: Scope): TryTaskState = TryTaskState(
        startedAt = Clock.System.now(),
        transformedInput = transformedInput,  // Store for retries/catch
        attemptIndex = -1,
        runningCatch = false,
        errorAs = node.task.catch?.`as` ?: "error"
    )

    /**
     * Determines the next step information based on the current `TryState`, dataset, node name, and scope.
     * This method handles both the first attempt (down) and the completion (up) of the `TryState` node.
     *
     * This method is used in "normal" execution.
     * This is bypassed by [WorkflowOrchestrator] when this node caught an exception.
     */
    override fun getNextStepInfo(
        state: TryTaskState,
        dataset: JsonElement,
        scope: Scope,
        namedNode: String?,
    ) = when (state.attemptIndex < 0) {
        // first attempt
        true -> NextStepInfo(
            updatedState = state.newAttemptState(),
            nextNode = getDoTry(),
            flowDirective = null
        )
        // completed, go to parent
        false -> NextStepInfo(
            updatedState = null,
            nextNode = node.parent,
            flowDirective = getFlowDirective()
        )
    }

    /**
     * Determines whether the current `TryTask` is catching a specified error based on its state,
     * configuration, and evaluation of filters.
     */
    internal fun isCatching(error: InternalWorkflowException.Error, state: TryTaskState, scope: Scope): Boolean {
        // running catch = true means we are running the catch block
        if (state.runningCatch) return false

        // return if no catch
        val catches: TryTaskCatch = node.task.catch ?: return false

        // testing `errors.with` directive
        catches.errors?.with?.let { filter: ErrorFilter ->
            if (filter.type != null && filter.type != error.type) return false
            if (filter.status > 0 && filter.status != error.status) return false
            if (filter.instance != null && filter.instance != error.instance) return false
            if (filter.title != null && filter.title != error.title) return false
            if (filter.details != null && filter.details != error.details) return false
        }


        // get transient scope with error
        val filterScope by lazy {
            buildJsonObject {
                scope.forEach { (key, value) -> put(key, value) }
                put(errorAs, LemlineJson.encodeToElement(error))
            }
        }

        // testing `when` directive
        catches.`when`?.let { whenExpr ->
            val whenFilter = evalBoolean(state.transformedInput, whenExpr, "when", filterScope)
            if (!whenFilter) return false
        }

        // testing `exceptWhen` directive
        catches.exceptWhen?.let { exceptWhen ->
            val exceptWhenFilter = evalBoolean(state.transformedInput, exceptWhen, "exceptWhen", filterScope)
            if (exceptWhenFilter) return false
        }

        return true
    }

    /**
     * Handles an error that occurred during the execution of the workflow and determines the next step.
     * Depending on the state and retry policy, it either retries by re-entering the try block or
     * moves to the catch block.
     */
    internal fun handleError(
        failingNode: Node<*>,
        error: InternalWorkflowException.Error,
        state: TryTaskState,
        scope: Scope
    ): StepResult {
        // Check if we should retry
        val shouldRetry = shouldRetry(state, scope)

        return when {
            // Retry if attempts remain
            shouldRetry -> StepResult(
                nextNode = getDoTry(),  // Re-enter try body
                rawInput = state.transformedInput,  // Original input
                stateUpdates = updatesToCleanState(failingNode, state.newAttemptState(error)),
                retryAt = Clock.System.now() + retryPolicy!!.getRetryDelay(state.attemptIndex)
            )
            // Otherwise, enter the catch block
            else -> StepResult(
                nextNode = getCatchNode(),  // Re-enter try body
                rawInput = state.transformedInput,  // Original input
                stateUpdates = updatesToCleanState(failingNode, state.toCatchState(error)),
            )
        }
    }

    private val errorAs = node.task.catch?.`as` ?: "error"

    /**
     * Lazily retrieve the Retry Policy of this node
     * - either by name
     * - either set directly
     */
    private val retryPolicy: RetryPolicy? by lazy {
        when (val retry = node.task.catch?.retry?.get()) {
            // from workflow.use
            is String -> getRootTask().use?.retries?.additionalProperties
                ?.get(retry)
                ?: error("Unknown retry policy name '$retry'")

            is RetryPolicy -> retry
            null -> null
            else -> error("Unknown retry policy: $retry")
        }
    }

    /**
     * Retrieves the "try" child node from the current node's children.
     */
    private fun getDoTry(): Node<*> = node.children?.getOrNull(0)
        ?: throw IllegalStateException("No try child found in TryTask ${node.reference}")

    /**
     * Retrieves the "catch" child node from the current node's children.
     */
    private fun getCatchNode(): Node<*> = node.children?.getOrNull(1)
        ?: throw IllegalStateException("No catch child found in TryTask ${node.reference}")

    /**
     * Updates the state of nodes to reflect a clean state, starting from a given failing node
     * up to a higher-level try node in the hierarchy.
     */
    private fun updatesToCleanState(
        failingNode: Node<*>,
        updatedState: TryTaskState,
    ): Map<NodePosition, TaskState?> {
        var previous = failingNode
        return buildMap {
            // Clean state of all nodes up to the try node
            do {
                put(previous.position, null)
                previous = previous.parent
                    ?: throw IllegalStateException("Current try node ${node.reference} not found on path of node ${failingNode.name}")
            } while (previous != node)
            // add the updated state of the Try node
            put(node.position, updatedState)
        }
    }

    /**
     * Check if should retry based on retry configuration and current attempt count.
     */
    private fun shouldRetry(state: TryTaskState, scope: Scope): Boolean {
        // get catch directive
        val retryConfig = retryPolicy ?: return false
        val retryLimit = retryConfig.limit?.attempt?.count ?: 1

        // Check if we've exhausted retry attempts
        if (state.attemptIndex >= retryLimit) {
            logger.debug { "retryPolicy.limit.attempt.count ($retryLimit) reached for node ${node.name}" }
            return false
        }

        retryConfig.`when`?.let { whenExpr ->
            if (!evalBoolean(state.transformedInput, whenExpr, "when", scope)) {
                logger.debug { "retryPolicy.when condition is false for node ${node.name}" }
                return false
            }
        }

        retryConfig.exceptWhen?.let { exceptWhen ->
            if (evalBoolean(state.transformedInput, exceptWhen, "exceptWhen", scope)) {
                logger.debug { "retryPolicy.exceptWhen condition is true for node ${node.name}" }
                return false
            }
        }

        // Max duration
        val durationLimit: Duration? = retryConfig.limit?.duration?.toDuration()
        if (durationLimit != null && (Clock.System.now() - state.startedAt) >= durationLimit) {
            logger.debug { "retryPolicy.limit.duration ($durationLimit) reached for node ${node.name}" }
            return false
        }

        return true
    }

    /**
     * Calculates the delay duration before the next retry attempt based on the retry policy and the current attempt index.
     * The method considers factors such as base delay, backoff strategy, and jitter to compute the final retry delay.
     */
    private fun RetryPolicy.getRetryDelay(attemptIndex: Int): Duration {

        // Max attempt duration before a task attempt timeout
        val attemptDurationLimit: Duration? = limit?.attempt?.duration?.toDuration()

        // start with the base delay
        var delay = delay.toDuration()

        // apply backoff if any
        delay = backoff?.get()?.let {
            when (it) {
                is ConstantBackoff -> delay
                is LinearBackoff -> delay * (1 + attemptIndex)
                is ExponentialBackOff -> delay.toDouble(DurationUnit.SECONDS).pow(1 + attemptIndex).seconds
                else -> error("Unknown backoff: $it")
            }
        } ?: delay

        // apply jitter if any
        delay += jitter.toRandomDuration()

        return when {
            delay <= Duration.ZERO -> {
                logger.debug { "retry delay calculated $delay for node ${node.name}, coerced to 0" }
                Duration.ZERO
            }

            attemptDurationLimit != null -> {
                if (delay > attemptDurationLimit) {
                    logger.debug { "retry delay calculated $delay for node ${node.name}, coerced to $attemptDurationLimit" }
                    attemptDurationLimit
                } else {
                    logger.debug { "retry delay calculated $delay for node ${node.name}" }
                    delay
                }
            }

            else -> {
                logger.debug { "retry delay calculated $delay for node ${node.name}" }
                delay
            }
        }
    }
}
