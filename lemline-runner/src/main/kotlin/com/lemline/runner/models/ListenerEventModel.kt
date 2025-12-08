// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Model representing an accumulated CloudEvent for a listener.
 *
 * Used for strategies that require multiple events:
 * - **ALL**: One event per filter, completion when all filters matched
 * - **ANY with until**: Accumulate events until condition is met
 *
 * For simple strategies (ONE, ANY without until), the event is stored
 * directly in the [ListenerModel.event] column.
 *
 * ## Foreach Support
 *
 * When foreach is enabled on the listener, this model also serves as an outbox
 * for sequential event processing. Events are processed one at a time through
 * foreach.do, with results stored in [iterationOutput].
 *
 * ## Idempotency
 *
 * Two unique constraints ensure idempotency:
 * - **ALL**: `UNIQUE(listener_id, filter_index)` - one event per filter
 * - **ANY+until**: `UNIQUE(listener_id, cloudevent_id)` - same CloudEvent not added twice
 *
 * ## Filter Index
 *
 * - **ALL strategy**: Explicit value (0, 1, 2...) matching the filter that was satisfied
 * - **ANY+until**: NULL (allows multiple events per listener)
 *
 * @see ListenerModel for the parent listener
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerEventModel(
    /** Unique identifier */
    override val id: IDV7,

    /** Reference to the parent listener */
    val listenerId: IDV7,

    /**
     * Filter index.
     * - For ALL: explicit filter index (0, 1, 2...)
     * - For ANY+until: null (allows multiple events)
     */
    val filterIndex: Int?,

    /**
     * CloudEvent ID for idempotency.
     * - For ALL: null (idempotency via filter_index)
     * - For ANY+until: CloudEvent.id to prevent duplicate events on retry
     */
    val cloudEventId: String?,

    /** CloudEvent data as JSON string */
    val event: String,

    /** Creation timestamp */
    val createdAt: Instant? = null,

    // ========================================
    // Foreach Outbox Fields
    // ========================================
    // These fields are used when the listener has foreach enabled.
    // They enable sequential processing of events through foreach.do.

    /** When outbox processing was scheduled (NULL = not foreach enabled) */
    override val outboxScheduledFor: Instant? = null,

    /** When event is ready for foreach processing (NULL = queued, waiting) */
    override var outboxDelayedUntil: Instant? = null,

    /** Number of processing attempts */
    override var outboxAttemptCount: Int = 0,

    /** Error class from last failed attempt */
    override var outboxErrorClass: String? = null,

    /** Error message from last failed attempt */
    override var outboxErrorMessage: String? = null,

    /** Error stack trace from last failed attempt */
    override var outboxErrorStackTrace: String? = null,

    /** When foreach.do completed for this event */
    override var outboxCompletedAt: Instant? = null,

    /** When foreach.do permanently failed for this event */
    override var outboxFailedAt: Instant? = null,

    // ========================================
    // Foreach Iteration Tracking
    // ========================================

    /** Foreach iteration index (0-based) */
    val iterationIndex: Int = 0,

    /** Output from foreach.do for this event (JSON) */
    var iterationOutput: String? = null,

    ) : WithId, WithOutbox {
    companion object
}
