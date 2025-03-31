package com.lemline.runtime.sw.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.runtime.sw.errors.WorkflowError
import com.lemline.runtime.sw.tasks.NodeInstance
import com.lemline.runtime.sw.tasks.NodeTask
import com.lemline.runtime.sw.utils.toDuration
import com.lemline.runtime.sw.utils.toRandomDuration
import io.serverlessworkflow.api.types.*
import io.serverlessworkflow.impl.json.JsonUtils
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class TryInstance(
    override val node: NodeTask<TryTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<TryTask>(node, parent) {

    // children[0] => try DoTask
    // children[1] (if any) => catch DoTask

    private val errorAs = node.task.catch.`as` ?: "error"

    // Retry policy
    private val retryPolicy: RetryPolicy? by lazy {
        when (val retry = node.task.catch?.retry?.get()) {
            // from workflow.use
            is String -> rootInstance.getRetryPolicy(retry)
            is RetryPolicy -> retry
            null -> null
            else -> error("Unknown retry policy: $retry")
        }
    }

    // Max number of retries
    private val retryLimit: Int? by lazy { retryPolicy?.limit?.attempt?.count }

    override fun `continue`(): NodeInstance<*>? {
        childIndex++

        return when (childIndex) {
            children.size -> then()
            else -> children[childIndex].also { it.rawInput = rawOutput!! }
        }
    }

    /**
     * Determines if this tryInstance is catching the specified error.
     *
     * This method evaluates the `catch` conditions defined in the `TryTask` to determine
     * if the current instance should handle the given `WorkflowError`.
     *
     * @param taskInput The input data for the task.
     * @param error The workflow error to be checked.
     * @return `true` if the error is caught by this instance, `false` otherwise.
     */
    internal fun isCatching(taskInput: JsonNode, error: WorkflowError): Boolean {
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
            scope.deepCopy().apply { set<JsonNode>(errorAs, JsonUtils.fromValue(error)) }
        }

        // testing `when` directive
        catches.`when`?.let { whenExpr ->
            val whenFilter = evalBoolean(taskInput, whenExpr, "when", filterScope)
            if (!whenFilter) return false
        }

        // testing `exceptWhen` directive
        catches.exceptWhen?.let { exceptWhen ->
            val exceptWhenFilter = evalBoolean(taskInput, exceptWhen, "exceptWhen", filterScope)
            if (exceptWhenFilter) return false
        }

        // add error to the current custom scope
        this.variables.set<JsonNode>(errorAs, JsonUtils.fromValue(error))

        return true
    }

    /**
     * Calculate the delay before the next retry attempt based on the retry configuration
     */
    fun getRetryDelay(error: WorkflowError, attemptIndex: Int): Duration? {
        // if no retry policy
        retryPolicy ?: return null

        // if attempt limit is reached, we do not retry
        retryLimit?.let {
            if (attemptIndex + 1 >= it) return null
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

        return delay
    }
} 