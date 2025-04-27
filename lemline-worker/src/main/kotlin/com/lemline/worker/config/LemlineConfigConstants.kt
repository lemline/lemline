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

    // H2 Default values
    const val DEFAULT_H2_DB_NAME = "lemline"
    const val DEFAULT_H2_USERNAME = "sa"
    const val DEFAULT_H2_PASSWORD = "sa"

    // Messaging types
    const val MSG_TYPE_IN_MEMORY = "in-memory"
    const val MSG_TYPE_KAFKA = "kafka"
    const val MSG_TYPE_RABBITMQ = "rabbitmq"

    // Messaging connectors
    const val IN_MEMORY_CONNECTOR = "smallrye-in-memory"
    const val KAFKA_CONNECTOR = "smallrye-kafka"
    const val RABBITMQ_CONNECTOR = "smallrye-rabbitmq"

}
