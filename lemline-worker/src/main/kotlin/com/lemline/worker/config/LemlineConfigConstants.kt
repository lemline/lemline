// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

/**
 * Constants for Lemline configuration.
 * These values are used throughout the configuration system.
 */
object LemlineConfigConstants {
    // Configuration source
    const val CONFIG_ORDINAL = 275
    const val CONFIG_SOURCE_NAME = "LemlineConfigSource"

    // Database types
    const val DB_TYPE_IN_MEMORY = "in-memory"
    const val DB_TYPE_POSTGRESQL = "postgresql"
    const val DB_TYPE_MYSQL = "mysql"
    val SUPPORTED_DB_TYPES = setOf(DB_TYPE_IN_MEMORY, DB_TYPE_POSTGRESQL, DB_TYPE_MYSQL)

    // H2 Default values
    const val DEFAULT_H2_DB_NAME = "lemline"
    const val DEFAULT_H2_USERNAME = "sa"
    const val DEFAULT_H2_PASSWORD = ""

    // Messaging types
    const val MSG_TYPE_IN_MEMORY = "in-memory"
    const val MSG_TYPE_KAFKA = "kafka"
    const val MSG_TYPE_RABBITMQ = "rabbitmq"
    val SUPPORTED_MSG_TYPES = setOf(MSG_TYPE_IN_MEMORY, MSG_TYPE_KAFKA, MSG_TYPE_RABBITMQ)

    // Messaging connectors
    const val IN_MEMORY_CONNECTOR = "smallrye-in-memory"
    const val KAFKA_CONNECTOR = "smallrye-kafka"
    const val RABBITMQ_CONNECTOR = "smallrye-rabbitmq"

    // Validation
    const val MIN_PORT = 1L
    const val MAX_PORT = 65535L
    const val MIN_BATCH_SIZE = 1L
    const val MAX_BATCH_SIZE = 10000L
}
