// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.common.json.LemlineJson
import com.lemline.core.errors.InternalException
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.TryState
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.TaskRetryScheduled
import com.lemline.core.states.WorkflowEvent.TaskScheduled
import com.lemline.core.utils.toDuration
import com.lemline.core.utils.toRandomDuration
import com.lemline.core.workflows.catchBlock
import com.lemline.core.workflows.tryBlock
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
) : NodeProcessor<TryTask, TryState>(node) {

    /**
     * This state is initialized when entering the TryTask node for the first time
     */
    override fun stateWhenEnteringFromParent(transformedInput: JsonElement, scope: Scope): TryState = TryState(
        startedAt = Clock.System.now(),
        transformedInput = transformedInput,  // Store for retries/catch
        attemptIndex = 0,  // Ready for first attempt
        runningCatch = false,
        errorAs = node.task.catch?.`as` ?: "error"
    )

    /**
     * Update state when re-entering from child after successful execution of the try block or the catch block.
     */
    override fun stateWhenReEnteringFromChild(
        state: TryState,
        output: JsonElement,
        scope: Scope,
        nodeName: String?
    ): TryState = state.copy(
        hasStarted = true
    )

    /**
     * Determines the next node based on the current `TryState`.
     * This method handles both the first attempt (down) and the completion (up) of the `TryState` node.
     *
     * For navigation purposes:
     * - !hasStarted: First attempt, go to try block
     * - hasStarted: Completed (after retry or catch), go to parent
     *
     * This is bypassed by [handleError] when this node caught an exception.
     */
    override fun getNextNode(
        state: TryState,
        dataset: JsonElement,
        scope: Scope,
    ): NavigationInfo = when (state.hasStarted) {
        // First attempt - go to try block
        false -> NavigationInfo(
            nextNode = getDoTry(),
            nextDirective = null
        )
        // Completed - go to parent
        true -> NavigationInfo(
            nextNode = node.parent,
            nextDirective = getFlowDirective()
        )
    }

    /**
     * Determines whether the current `TryTask` is catching a specified error based on its state,
     * configuration, and evaluation of filters.
     */
    internal fun isCatching(error: InternalException.Error, state: TryState, scope: Scope): Boolean {
        // running catch = true means we are running the catch block
        if (state.runningCatch) return false

        // return if no catch
        val catches: TryTaskCatch = node.task.catch ?: return false

        // testing `errors.with` directive
        catches.errors?.with?.let { filter: ErrorFilter ->
            if (filter.type != null && filter.type != error.type) return false
            if (filter.status > 0 && filter.status != error.status) return false
            if (filter.instance != null && filter.instance != error.position) return false
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
        error: InternalException.Error,
        state: TryState,
        nodeStack: NodeStack
    ): WorkflowEvent {

        // Check if we should retry
        val shouldRetry = shouldRetry(state, nodeStack.stateScope)

        return when (shouldRetry) {
            // Retry if attempts remain
            true -> {
                val updatedState = state.copy(
                    attemptIndex = state.attemptIndex + 1
                )
                TaskRetryScheduled(
                    nodeStack = cleanStateStack(updatedState, nodeStack),
                    nodePosition = getDoTry().position,
                    rawInput = state.transformedInput,
                    flowDirective = null,
                    retryAt = Clock.System.now() + retryPolicy!!.getRetryDelay(updatedState.attemptIndex)
                )
            }
            // Otherwise, enter the catch block
            false -> {
                val updatedState = state.copy(
                    runningCatch = true,
                    lastError = error
                )
                TaskScheduled(
                    nodeStack = cleanStateStack(updatedState, nodeStack),
                    nodePosition = getCatchNode().position,
                    rawInput = state.transformedInput,
                    flowDirective = null,
                )
            }
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
            is String -> use?.retries?.additionalProperties
                ?.get(retry)
                ?: error("Unknown retry policy name '$retry'")

            is RetryPolicy -> retry
            null -> null
            else -> error("Unknown retry policy: $retry")
        }
    }

    /**
     * Retrieves the "try" child node from the current node's children.
     * @see com.lemline.core.workflows.tryBlock
     */
    private fun getDoTry(): Node<*> = node.tryBlock

    /**
     * Retrieves the "catch" child node from the current node's children.
     * @see com.lemline.core.workflows.catchBlock
     */
    private fun getCatchNode(): Node<*> = node.catchBlock
        ?: throw IllegalStateException("No catch child found in TryTask ${node.position}")

    private fun cleanStateStack(updatedState: TryState, nodeStack: NodeStack): NodeStack =
        nodeStack.popUntil(node.position).incrementTopCounter().updateTopState(updatedState)

    /**
     * Check if should retry based on retry configuration and current attempt count.
     */
    private fun shouldRetry(state: TryState, scope: Scope): Boolean {
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
