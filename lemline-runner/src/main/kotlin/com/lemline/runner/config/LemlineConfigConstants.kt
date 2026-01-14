// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

/**
 * Constants for Lemline configuration.
 * These values are used throughout the configuration system.
 */
object LemlineConfigConstants {
    // Configuration source
    const val CONFIG_ORDINAL = 275
    const val CONFIG_SOURCE_NAME = "LemlineConfigSource"

    const val DB_MIGRATE_AT_START_DEFAULT = "false"
    const val DB_BASELINE_ON_MIGRATE_DEFAULT = "false"

    // Consumer concurrency
    const val CONSUMER_CONCURRENCY_DEFAULT = "64"

    // Messaging connectors
    const val IN_MEMORY_CONNECTOR = "smallrye-in-memory"
    const val KAFKA_CONNECTOR = "smallrye-kafka"
    const val RABBITMQ_CONNECTOR = "smallrye-rabbitmq"
    const val PGMQ_CONNECTOR = "smallrye-pgmq"

    const val COMMANDS_TOPIC_DEFAULT = "lemline-commands"
    const val EVENTS_TOPIC_DEFAULT = "lemline-events"
    const val CLOUDEVENTS_TOPIC_DEFAULT = "lemline-cloudevents"
    const val LIFECYCLE_EVENTS_TOPIC_DEFAULT = "lemline-lifecycle-events"

    // Postgres
    const val POSTGRES_HOST_DEFAULT = "localhost"
    const val POSTGRES_PORT_DEFAULT = "5432"
    const val POSTGRES_DATABASE_DEFAULT = "lemline"
    const val POSTGRES_USERNAME_DEFAULT = "postgres"
    const val POSTGRES_PASSWORD_DEFAULT = "postgres"

    // MySQL
    const val MYSQL_HOST_DEFAULT = "localhost"
    const val MYSQL_PORT_DEFAULT = "3306"
    const val MYSQL_DATABASE_DEFAULT = "lemline"
    const val MYSQL_USERNAME_DEFAULT = "mysql"
    const val MYSQL_PASSWORD_DEFAULT = "mysql"

    // H2
    const val H2_DB_NAME_DEFAULT = "lemline"
    const val H2_USERNAME_DEFAULT = "sa"
    const val H2_PASSWORD_DEFAULT = "sa"

    // Kafka
    const val KAFKA_BROKERS_DEFAULT = "localhost:9092"
    const val KAFKA_OFFSET_RESET_DEFAULT = "earliest"
    const val KAFKA_STRING_SERIALIZER = "org.apache.kafka.common.serialization.StringSerializer"
    const val KAFKA_STRING_DESERIALIZER = "org.apache.kafka.common.serialization.StringDeserializer"
    const val KAFKA_WORKFLOWS_GROUP_ID_DEFAULT = "lemline-group"
    const val KAFKA_DATABASE_GROUP_ID_DEFAULT = "lemline-group"
    const val KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT = "lemline-cloudevents-group"
    const val KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT = "lemline-lifecycle-events-group"

    // RabbitMQ
    const val RABBITMQ_HOST_DEFAULT = "localhost"
    const val RABBITMQ_PORT_DEFAULT = "5672"
    const val RABBITMQ_USER_DEFAULT = "guest"
    const val RABBITMQ_PASSWORD_DEFAULT = "guest"
    const val RABBITMQ_VHOST_DEFAULT = "/"
    const val RABBITMQ_STRING_SERIALIZER = "java.lang.String"

    // Metrics
    const val METRICS_PORT_DEFAULT = "8080"
    const val METRICS_PATH_DEFAULT = "/q/metrics"

    // PGMQ (PostgreSQL Message Queue)
    const val PGMQ_VISIBILITY_TIMEOUT_DEFAULT = "30"
    const val PGMQ_POLL_INTERVAL_DEFAULT = "100"
    const val PGMQ_BATCH_SIZE_DEFAULT = "10"
    const val PGMQ_MAX_POOL_SIZE_DEFAULT = "5"
    const val PGMQ_MAX_RETRIES_DEFAULT = "3"
    const val PGMQ_COMMANDS_GROUP_ID_DEFAULT = "lemline-commands-group"
    const val PGMQ_EVENTS_GROUP_ID_DEFAULT = "lemline-events-group"
}
