// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.postgres.connector

import java.time.Instant

/**
 * Metadata attached to incoming PGMQ messages.
 *
 * This metadata provides information about the message origin and delivery state.
 */
data class PgmqIncomingMetadata(
    /** The unique message ID in PGMQ */
    val msgId: Long,
    /** Number of times this message has been read (delivery attempts) */
    val readCount: Int,
    /** When the message was originally enqueued */
    val enqueuedAt: Instant,
    /** When the message visibility timeout expires */
    val visibilityTimeout: Instant,
    /** The queue this message came from */
    val queue: String,
)

/**
 * Metadata for outgoing PGMQ messages.
 *
 * Can be attached to outgoing messages to control delivery behavior.
 */
data class PgmqOutgoingMetadata(
    /** Delay in seconds before the message becomes visible */
    val delaySeconds: Int = 0,
    /** Custom message ID (optional, will be generated if not provided) */
    val messageId: String? = null,
)
