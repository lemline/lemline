// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.config

/**
 * Supported messaging/broker types for Lemline.
 *
 * Using an enum provides:
 * - Type safety: compiler catches invalid values
 * - Exhaustive `when` expressions: compiler warns if cases are missing
 * - Better IDE support: autocomplete and refactoring
 */
enum class MessagingType(val configValue: String) {
    /** In-memory messaging (for testing and development) */
    IN_MEMORY("in-memory"),

    /** Apache Kafka */
    KAFKA("kafka"),

    /** RabbitMQ */
    RABBITMQ("rabbitmq"),

    /** PGMQ (PostgreSQL-based message queue) */
    PGMQ("pgmq");

    companion object {
        /**
         * Converts a configuration string value to MessagingType.
         * @throws IllegalArgumentException if the value doesn't match any known type
         */
        fun fromConfigValue(value: String): MessagingType =
            entries.find { it.configValue == value }
                ?: throw IllegalArgumentException("Unknown messaging type: '$value'")
    }
}
