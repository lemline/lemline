// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite.connector

import java.time.Instant

/** Logical identity and routing of the published message. */
data class SqliteOutgoingMetadata(
    val messageId: String? = null,
    val queue: String? = null,
    val delaySeconds: Int = 0,
)

/** Delivery information from SQLite. The logical ID survives redelivery. */
data class SqliteIncomingMetadata(
    val messageId: String,
    val queue: String,
    val readCount: Int,
    val enqueuedAt: Instant,
    val visibilityTimeout: Instant,
)
