package com.lemline.sw.nodes.flows

import com.lemline.common.json.Json
import com.lemline.sw.errors.WorkflowError
import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.utils.toDuration
import com.lemline.sw.utils.toRandomDuration
import io.serverlessworkflow.api.types.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class TryInstance(
    override val node: Node<TryTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<TryTask>(node, parent) {

    private val errorAs = node.task.catch.`as` ?: "error"

    private val tryDoInstance by lazy { children[0] as DoInstance }
    internal val catchDoInstance by lazy { children[1] as DoInstance? }

    internal var delay: Duration? = Duration.ZERO

    /**
     * delay is a transient value, not part of the state
     */
    override fun reset() {
        delay = Duration.ZERO
        super.reset()
    }

    /**
     * First time we enter the node, we continue to the tryDoInstance
     * If we return, this means the processing was successful
     */
    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            0 -> tryDoInstance.also { it.rawInput = transformedInput }
            else -> then()
        }
    }

    /**
     * Lazily retrieve the Retry Policy
     * - either by name
     * - either set directly
     */
    private val retryPolicy: RetryPolicy? by lazy {
        when (val retry = node.task.catch?.retry?.get()) {
            // from workflow.use
            is String -> rootInstance.getRetryPolicy(retry)
            is RetryPolicy -> retry
            null -> null
            else -> error("Unknown retry policy: $retry")
        }
    }

    /**
     * Maximum number of retries
     */
    private val retryLimit: Int? by lazy { retryPolicy?.limit?.attempt?.count }

    /**
     * Current Index of attempts (0 => first attempt, 1 => first retry, 2 => second retry, ...)
     */
    internal var attemptIndex
        get() = state.attemptIndex
        set(value) {
            state.attemptIndex = value
        }

    override fun shouldStart(): Boolean {
        // if has already started and is retrying
        if (attemptIndex > 0) return true
        return super.shouldStart()
    }

    /**
     * Determines if this tryInstance is catching the specified error.
     *
     * This method evaluates the `catch` conditions defined in the `TryTask` to determine
     * if the currentNodeInstance initialPosition should handle the given `WorkflowError`.
     *
     * @param error The workflow error to be checked.
     * @return `true` if the error is caught by this initialPosition, `false` otherwise.
     */
    internal fun isCatching(error: WorkflowError): Boolean {
        val catches: TryTaskCatch = node.task.catch ?: return false

        // testing `errors.with` directive
        catches.errors?.with?.let { filter ->
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
                put(errorAs, Json.encodeToElement(error))
            }
        }

        // testing `when` directive
        catches.`when`?.let { whenExpr ->
            val whenFilter = evalBoolean(transformedInput, whenExpr, "when", filterScope)
            if (!whenFilter) return false
        }

        // testing `exceptWhen` directive
        catches.exceptWhen?.let { exceptWhen ->
            val exceptWhenFilter = evalBoolean(transformedInput, exceptWhen, "exceptWhen", filterScope)
            if (exceptWhenFilter) return false
        }

        // add error to the currentNodeInstance custom scope
        variables = JsonObject(mapOf(errorAs to Json.encodeToElement(error)))

        // new attempt
        attemptIndex++

        // get delay before retry
        delay = getRetryDelay(error)

        return when (delay) {
            // if we don't retry, catch this error only if catchDoInstance is not null
            null -> (catchDoInstance?.children?.size !in setOf(0, null)) // if no catch, we don't retry
            // if we retry, set the delay
            else -> true
        }
    }

    /**
     * Calculate the delay before the next retry attempt based on the retry configuration
     */
    private fun getRetryDelay(error: WorkflowError): Duration? {
        // if no retry policy
        retryPolicy ?: return null

        // if attempt limit is reached, we do not retry
        retryLimit?.let {
            if (attemptIndex > it) return null
        }

        // Max attempt duration before a task attempt timeout
        val attemptDurationLimit: Duration? = retryPolicy?.limit?.attempt?.duration?.toDuration()

        // Max duration before a task timeout
        val durationLimit: Duration? = retryPolicy?.limit?.duration?.toDuration()

        // testing `when` directive
        retryPolicy?.`when`?.let { whenExpr ->
            val whenFilter = evalBoolean(transformedInput, whenExpr, "when")
            if (!whenFilter) return null
        }

        // testing `exceptWhen` directive
        retryPolicy?.exceptWhen?.let { exceptWhen ->
            val exceptWhenFilter = evalBoolean(transformedInput, exceptWhen, "exceptWhen")
            if (exceptWhenFilter) return null
        }

        // start with the base delay
        var delay = retryPolicy!!.delay.toDuration()

        // apply backoff if any
        delay = retryPolicy?.backoff?.get()?.let {
            when (it) {
                is ConstantBackoff -> delay
                is LinearBackoff -> delay * (1 + attemptIndex)
                is ExponentialBackOff -> delay.toDouble(DurationUnit.SECONDS).pow(1 + attemptIndex).seconds
                else -> error("Unknown backoff: $it")
            }
        } ?: delay

        // apply jitter if any
        delay += retryPolicy?.jitter.toRandomDuration()

        return if (delay > Duration.ZERO) delay.also { this.delay = it } else null
    }
} 