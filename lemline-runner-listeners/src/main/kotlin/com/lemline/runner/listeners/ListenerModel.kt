// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithId
import com.lemline.runner.common.models.WithInstanceMessage
import com.lemline.runner.common.models.WithOutbox
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Model representing an active listener waiting for CloudEvents.
 *
 * A listener is created when a workflow reaches a `listen` task and needs
 * to wait for external events before continuing execution.
 *
 * ## Simplified Architecture
 *
 * All events (for ALL strategies) are stored in `lemline_listener_events` table.
 * State tracking uses standard outbox columns + `ready_at` for completion.
 *
 * The listener stores:
 * - Workflow identity (namespace, name, version) for matching against cached workflow definitions
 * - Workflow position (for locating the listen task in the workflow tree)
 * - Workflow instance identity (for resuming the workflow when events match)
 * - Configuration flags (hasForeach, hasUntil, filtersCount)
 * - Outbox fields (for reliable processing and cleanup)
 *
 * ## Listen Task Configuration
 *
 * Strategy, filters, and readAs are retrieved from the cached workflow definition
 * using (workflowNamespace, workflowName, workflowVersion, workflowPosition).
 *
 * ## Completion Flow
 *
 * 1. CloudEvents arrive → INSERT into listener_events
 * 2. If hasForeach: ListenerForeachOutbox processes events sequentially
 * 3. When completion criteria met: ready_at is set
 * 4. ListenerCompletionOutbox emits resume command
 *
 * ## Correlation
 *
 * Listeners may require events to match specific correlation values.
 * For Mode 2 (first-sets-baseline), the first event sets the baseline
 * and subsequent events must match.
 *
 * @see WorkflowEvent.ListenStarted for the triggering event
 * @see ListenerEventModel for accumulated events
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerModel(
    /** Unique identifier for this listener */
    override val id: IDV7,

    /** Workflow instance message containing state for resumption */
    override val instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,

    /** Listen strategy: ONE, ANY, ANY_UNTIL_EXPR, ANY_UNTIL_EVENT, ALL */
    val listenerStrategy: ListenerStrategy,

    /** Timestamp when the listener times out (null = no timeout) */
    val timeoutAt: Instant?,
) : WithId, WithInstanceMessage, WithOutbox, WithCleanup {

    /** Total number of filters for ALL strategy (null for other strategies) */
    var filtersCount: Int? = null

    /** TRUE if listener has an until condition */
    var hasUntil: Boolean = false

    /** JQ expression for ANY_UNTIL_EXPR strategy (null for other strategies) */
    var untilExpression: String? = null

    /** TRUE if listener has foreach.do configured */
    var hasForeach: Boolean = false

    /** Correlation baseline values (Mode 2: first-sets-baseline), JSON map */
    var correlationValues: String? = null

    /** Timestamp when completion criteria were met (triggers ListenerCompletionOutbox) */
    var readyAt: Instant? = null

    /**
     * Gets the readAs mode from the cached workflow definition.
     * Defaults to DATA if the definition is not found.
     */
    val readAs: ListenAndReadAs by lazy {
        val listenTasks = WorkflowCache.getListenTasks(workflowInfo)
        val listenTask = listenTasks.find { it.nodePosition == nodePosition }

        listenTask?.readAs ?: ListenAndReadAs.DATA
    }

    /**
     * Transforms a given `cloudEvent` into a `JsonElement` based on the `readAs` configuration.
     *
     * The transformation behavior depends on the value of `readAs`:
     * - `ListenAndReadAs.DATA`: Extracts and returns the "data" field from the CloudEvent, or `JsonNull` if it is absent.
     * - `ListenAndReadAs.ENVELOPE`: Returns the entire `cloudEvent` object.
     * - `ListenAndReadAs.RAW`: Returns the raw data in base64 (if the "data_base64" field exists), or the "data" field
     *   as-is transformed into a primitive type string. If none of these fields are present, returns `JsonNull`.
     *
     * @param cloudEvent The CloudEvent data as a `JsonObject`.
     * @return The transformed `JsonElement` based on the configured `readAs` value.
     */
    fun applyReadAs(cloudEvent: JsonObject): JsonElement = when (readAs) {
        ListenAndReadAs.DATA -> cloudEvent["data"] ?: JsonNull
        ListenAndReadAs.ENVELOPE -> cloudEvent // Return full CloudEvent
        ListenAndReadAs.RAW -> {
            // Return raw data as string (for non-JSON payloads)
            cloudEvent["data_base64"]?.let { return it }
            cloudEvent["data"]?.let {
                it as? JsonPrimitive ?: JsonPrimitive(it.toString())
            } ?: JsonNull
        }
    }

    // Outbox fields
    // NOTE: outboxDelayedUntil starts as NULL (waiting state).
    // It gets set to NOW() when ready_at is set, triggering ListenerCompletionOutbox.
    override var outboxScheduledFor: Instant? = null
    override var outboxDelayedUntil: Instant? = null
    override var outboxAttemptCount: Int = 0
    override var outboxErrorClass: String? = null
    override var outboxErrorMessage: String? = null
    override var outboxErrorStackTrace: String? = null
    override var outboxCompletedAt: Instant? = null
    override var outboxFailedAt: Instant? = null
    override var cleanupAfter: Instant? = null

    companion object Companion
}
