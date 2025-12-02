// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.processors.ListenConfig
import com.lemline.core.processors.ListenStrategy
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
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
 * - Workflow identity (for resuming the workflow when events match)
 * - Listen configuration (strategy, filters, correlation, timeout)
 * - Progress tracking (accumulated events, matched filter indices)
 * - Outbox fields (for reliable processing and cleanup)
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
 * @see ListenConfig for configuration details
 * @see WorkflowEvent.ListenStarted for the triggering event
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerModel(
    /** Unique identifier for this listener */
    override val id: IDV7,

    /** Workflow instance message containing state for resumption */
    override val instanceMessage: InstanceMessage<WorkflowEvent.ListenStarted>,

    /** Workflow instance ID (for resuming) */
    val workflowId: WorkflowId,

    /** Workflow definition namespace */
    override val workflowNamespace: WorkflowNamespace,

    /** Workflow definition name */
    override val workflowName: WorkflowName,

    /** Workflow definition version */
    override val workflowVersion: WorkflowVersion,

    /** Position of the listen task in the workflow */
    val workflowPosition: NodePosition,

    /** Event consumption strategy */
    val strategy: ListenStrategy,

    /** How to read event data */
    val readAs: ListenAndReadAs,

    /** Serialized ListenConfig JSON */
    val config: String,

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

    /**
     * Parse the listen config from the stored JSON.
     */
    fun parseConfig(): ListenConfig {
        return Json.decodeFromString(config)
    }

    companion object Companion
}
