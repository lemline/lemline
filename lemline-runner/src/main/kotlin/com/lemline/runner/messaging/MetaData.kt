// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.IDV7

data class MetaData(
    val messageId: IDV7,
    /** Per-namespace destination name (Kafka topic / PGMQ queue); null = the channel's default. */
    val destination: String? = null,
    /** Namespace of the message, used as RabbitMQ routing key; null = the channel's default. */
    val namespace: String? = null,
) {
    companion object {
        const val MESSAGE_ID = "messageId"
    }
}
