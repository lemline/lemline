// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.pgmq

import java.time.Instant
import kotlinx.serialization.Serializable

/**
 * Represents a message from PGMQ.
 *
 * @property msgId Unique message identifier
 * @property readCt Number of times this message has been read
 * @property enqueuedAt When the message was enqueued
 * @property vt Visibility timeout - message becomes visible again after this time
 * @property message The message payload as JSON string
 * @property headers Optional message headers as JSON string
 */
data class PgmqMessage(
    val msgId: Long,
    val readCt: Int,
    val enqueuedAt: Instant,
    val vt: Instant,
    val message: String,
    val headers: String? = null,
)

/**
 * Message format for dead-letter queue.
 *
 * @property originalMessageId The message ID from the original queue
 * @property originalQueue The queue name where the message originated
 * @property payload The original message payload
 * @property error The error that caused the message to be moved to DLQ
 * @property timestamp ISO-8601 timestamp when the message was moved to DLQ
 */
@Serializable
data class DlqMessage(
    val originalMessageId: Long,
    val originalQueue: String,
    val payload: String,
    val error: String,
    val timestamp: String,
)

/**
 * A row read from a queue that could not be decoded into a [PgmqMessage].
 *
 * @property msgId The message ID in [queue]
 * @property queue The queue the row was read from
 * @property rawPayload Best-effort text of the stored payload (null if unavailable)
 * @property error Why decoding failed
 */
data class UnreadableMessage(
    val msgId: Long,
    val queue: String,
    val rawPayload: String?,
    val error: Throwable,
)
