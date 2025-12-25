// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Model representing a CloudEvent stored for a listener.
 *
 * ## Composite Key
 *
 * This model uses a composite primary key `(listenerId, eventId, filterIndex)` where:
 * - `eventId` is the CloudEvent's unique identifier
 * - `filterIndex` identifies which filter the event matched
 *
 * This allows the same CloudEvent to satisfy multiple filters in an ALL strategy,
 * while providing natural idempotency for duplicate events.
 *
 * ## Simplified Architecture
 *
 * ALL events (for ALL strategies) are stored in this table.
 * This model serves as both event storage AND foreach outbox.
 *
 * ## FIFO Ordering
 *
 * Events are processed in arrival order via the `created_at` column:
 * - First event for a listener gets `outbox_delayed_until = NOW` (immediately ready)
 * - Subsequent events get `outbox_delayed_until = NULL` (waiting for FIFO turn)
 * - When an event completes, the next event's `delayed_until` is set to NOW
 *
 * ## State Tracking via Outbox Columns
 *
 * | State | outbox_delayed_until | outbox_completed_at |
 * |-------|---------------------|---------------------|
 * | Waiting for FIFO | NULL | NULL |
 * | Ready for processing | NOT NULL, <= NOW | NULL |
 * | Processing (claimed) | NOT NULL | NULL (in-flight) |
 * | Completed | (any) | NOT NULL |
 * | Skipped (no foreach) | (any) | = created_at |
 *
 * ## Filter Index
 *
 * - **ALL strategy**: Explicit value (0, 1, 2...) for completion check
 * - **ONE/ANY strategies**: Defaults to 0 (single event per listener)
 *
 * @see ListenerModel for the parent listener
 */
@ExperimentalSerializationApi
@ExperimentalTime
data class ListenerEventModel(
    /** Reference to the parent listener (part of composite PK) */
    val listenerId: IDV7,

    /** CloudEvent ID from the CloudEvent spec 'id' field (part of composite PK) */
    val eventId: String,

    /**
     * Filter index that matched (part of composite PK).
     * - ALL strategy: Explicit value (0, 1, 2...) for completion check
     * - ONE/ANY strategies: Defaults to 0 (only one event stored per listener)
     */
    val filterIndex: Int = 0,

    /** CloudEvent as JSON string */
    val event: String,

    /** When outbox processing was scheduled (NULL for non-foreach events) */
    override val outboxScheduledFor: Instant? = null,
) : WithOutbox, WithCleanup {

    /** Whether foreach.do has completed for this event (used for efficient indexing) */
    var foreachCompleted: Boolean = false

    /** Output from foreach.do iteration (captured after completion) */
    var foreachOutput: String? = null

    /** Creation timestamp */
    var createdAt: Instant? = null

    // Standard outbox fields for foreach processing
    /** NULL = waiting for FIFO turn, NOT NULL = ready for processing */
    override var outboxDelayedUntil: Instant? = null

    /** Number of processing attempts */
    override var outboxAttemptCount: Int = 0

    /** Error class from last failed attempt */
    override var outboxErrorClass: String? = null

    /** Error message from last failed attempt */
    override var outboxErrorMessage: String? = null

    /** Error stack trace from last failed attempt */
    override var outboxErrorStackTrace: String? = null

    /** When foreach.do completed for this event (or immediate if no foreach) */
    override var outboxCompletedAt: Instant? = null

    /** When foreach.do permanently failed for this event */
    override var outboxFailedAt: Instant? = null

    /** When to delete this event */
    override var cleanupAfter: Instant? = null

    companion object
}
