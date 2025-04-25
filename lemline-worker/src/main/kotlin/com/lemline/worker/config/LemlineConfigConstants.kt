// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.config

/**
 * Constants for Lemline configuration.
 * These values are used throughout the configuration system.
 */
object LemlineConfigConstants {
    // Configuration source
    const val CONFIG_ORDINAL = 275
    const val CONFIG_SOURCE_NAME = "LemlineGeneratedConfigSource"

    // Database types
    const val DB_TYPE_POSTGRESQL = "postgresql"
    const val DB_TYPE_MYSQL = "mysql"
    const val DB_TYPE_H2 = "h2"
    val SUPPORTED_DB_TYPES = setOf(DB_TYPE_POSTGRESQL, DB_TYPE_MYSQL, DB_TYPE_H2)

    // Default values
    const val DEFAULT_DB_TYPE = DB_TYPE_H2
    const val DEFAULT_H2_DB_NAME = "testdb"
    const val DEFAULT_H2_USERNAME = "sa"
    const val DEFAULT_H2_PASSWORD = ""

    // Messaging types
    const val MSG_TYPE_KAFKA = "kafka"
    const val MSG_TYPE_RABBITMQ = "rabbitmq"
    val SUPPORTED_MSG_TYPES = setOf(MSG_TYPE_KAFKA, MSG_TYPE_RABBITMQ)

    // Connectors
    const val KAFKA_CONNECTOR = "smallrye-kafka"
    const val RABBITMQ_CONNECTOR = "smallrye-rabbitmq"

    // Validation
    const val MIN_PORT: Long = 1L
    const val MAX_PORT: Long = 65535L
    const val MIN_BATCH_SIZE: Long = 1L
    const val MAX_BATCH_SIZE: Long = 1000L
} 