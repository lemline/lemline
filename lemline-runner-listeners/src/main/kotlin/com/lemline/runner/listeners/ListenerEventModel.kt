// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.listeners

import com.lemline.common.values.IDV7
import com.lemline.runner.common.models.WithCleanup
import com.lemline.runner.common.models.WithOutbox
import kotlin.time.Instant

/**
 * Model representing a CloudEvent stored for a listener.
 *
 * ## Composite Key
 *
 * Queue entries are identified by `(listenerId, sortKey)`.
 * One CloudEvent may satisfy multiple ALL filters. Delivery deduplication uses
 * per-listener source + id receipts, independently of queue entry cleanup.
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
data class ListenerEventModel(
    val listenerId: IDV7,
    val eventId: String,
    val filterIndex: Int?,
    val event: String,
    var sortKey: Int = 0,
    var foreachCompleted: Boolean = false,
    var foreachOutput: String? = null,
    var createdAt: Instant? = null,
    override val outboxScheduledFor: Instant? = null,
    override var outboxDelayedUntil: Instant? = null,
    override var outboxAttemptCount: Int = 0,
    override var outboxErrorClass: String? = null,
    override var outboxErrorMessage: String? = null,
    override var outboxErrorStackTrace: String? = null,
    override var outboxCompletedAt: Instant? = null,
    override var outboxFailedAt: Instant? = null,
    override var cleanupAfter: Instant? = null,
) : WithOutbox, WithCleanup
