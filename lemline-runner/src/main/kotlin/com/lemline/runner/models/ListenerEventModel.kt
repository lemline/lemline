// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Model representing an accumulated CloudEvent for a listener.
 *
 * Implements [WithId] to enable [WithIdRepository] operations.
 *
 * Used for strategies that require multiple events:
 * - **ALL**: One event per filter, completion when all filters matched
 * - **ANY with until**: Accumulate events until condition is met
 *
 * For simple strategies (ONE, ANY without until), the event is stored
 * directly in the [ListenerModel.event] column.
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
    val createdAt: Instant? = null
) : WithId
