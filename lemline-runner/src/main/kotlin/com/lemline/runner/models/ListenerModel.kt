// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.messaging.InstanceMessage
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Model representing an active listener waiting for CloudEvents.
 *
 * A listener is created when a workflow reaches a `listen` task and needs
 * to wait for external events before continuing execution.
 *
 * The listener stores:
 * - Workflow identity (namespace, name, version) for matching against cached workflow definitions
 * - Workflow position (for locating the listen task in the workflow tree)
 * - Workflow instance identity (for resuming the workflow when events match)
 * - Progress tracking (accumulated events, matched filter indices)
 * - Outbox fields (for reliable processing and cleanup)
 *
 * ## Listen Task Configuration
 *
 * Strategy, filters, and readAs are retrieved from the cached workflow definition
 * using (workflowNamespace, workflowName, workflowVersion, workflowPosition).
 *
 * ## Strategies
 *
 * - **ONE**: Complete on first matching event
 * - **ANY**: Complete on any matching event (or accumulate with `until`)
 * - **ALL**: Wait for one event per filter
 *
 * ## Correlation
 *
 * Listeners may require events to match specific correlation values.
 * For Mode 2 (first-sets-baseline), the first event sets the baseline
 * and subsequent events must match.
 *
 * @see WorkflowEvent.ListenStarted for the triggering event
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerModel(
    /** Unique identifier for this listener */
    override val id: IDV7,

    /** Workflow namespace (for locating listen task in cached workflow definition) */
    override val workflowNamespace: WorkflowNamespace,

    /** Workflow name (for locating listen task in cached workflow definition) */
    override val workflowName: WorkflowName,

    /** Workflow version (for locating listen task in cached workflow definition) */
    override val workflowVersion: WorkflowVersion,

    /** Workflow instance message containing state for resumption */
    override val instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,

    /** Workflow instance ID (for resuming) */
    val workflowId: WorkflowId,

    /** Position of the listen task in the workflow */
    val workflowPosition: NodePosition,

    /** Timestamp when the listener times out (null = no timeout) */
    val timeoutAt: Instant?,

    /** Timestamp when this listener was scheduled for processing */
    override val outboxScheduledFor: Instant,
) : OutboxModel() {

    /** Correlation baseline values (Mode 2: first-sets-baseline), JSON map */
    var correlationValues: String? = null

    /** Accumulated events for ANY with until, JSON array */
    var accumulatedEvents: String? = null

    /** Matched filter indices for ALL strategy, JSON array of integers */
    var matchedFilterIndices: String? = null

    // Outbox fields
    override var outboxDelayedUntil: Instant? = outboxScheduledFor
    override var outboxAttemptCount: Int = 0
    override var outboxErrorClass: String? = null
    override var outboxErrorMessage: String? = null
    override var outboxErrorStackTrace: String? = null
    override var outboxCompletedAt: Instant? = null
    override var outboxFailedAt: Instant? = null

    /**
     * Get the list of matched filter indices (for ALL strategy).
     */
    fun getMatchedFilterIndices(): List<Int> {
        return matchedFilterIndices?.let {
            Json.parseToJsonElement(it).jsonArray.map { idx -> idx.jsonPrimitive.content.toInt() }
        } ?: emptyList()
    }

    /**
     * Check if a filter index has already been matched (for ALL strategy).
     */
    fun hasMatchedFilterIndex(index: Int): Boolean {
        return getMatchedFilterIndices().contains(index)
    }

    /**
     * Add a filter index to the matched list (for ALL strategy).
     * Returns the new JSON string.
     */
    fun addMatchedFilterIndex(index: Int): String {
        val current = getMatchedFilterIndices().toMutableList()
        if (index !in current) {
            current.add(index)
        }
        return Json.encodeToString(JsonArray(current.sorted().map { JsonPrimitive(it) }))
    }

    companion object Companion
}
