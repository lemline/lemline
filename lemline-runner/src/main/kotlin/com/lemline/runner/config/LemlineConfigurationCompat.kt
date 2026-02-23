// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

typealias LemlineConfiguration = com.lemline.runner.config.shared.LemlineConfiguration

/**
 * Backward-compatible aliases for code that still imports constants from
 * com.lemline.runner.config while configuration ownership lives in
 * lemline-runner-config.
 */
object LemlineConfigConstants {
    const val CONFIG_ORDINAL = com.lemline.runner.config.shared.LemlineConfigConstants.CONFIG_ORDINAL
    const val CONFIG_SOURCE_NAME = com.lemline.runner.config.shared.LemlineConfigConstants.CONFIG_SOURCE_NAME

    const val DB_MIGRATE_AT_START_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.DB_MIGRATE_AT_START_DEFAULT
    const val DB_BASELINE_ON_MIGRATE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.DB_BASELINE_ON_MIGRATE_DEFAULT

    const val CONSUMER_CONCURRENCY_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.CONSUMER_CONCURRENCY_DEFAULT
    const val ANALYTICS_CONSUMER_ENABLED_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_CONSUMER_ENABLED_DEFAULT
    const val ANALYTICS_CONSUMER_CONCURRENCY_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_CONSUMER_CONCURRENCY_DEFAULT

    const val IN_MEMORY_CONNECTOR = com.lemline.runner.config.shared.LemlineConfigConstants.IN_MEMORY_CONNECTOR
    const val KAFKA_CONNECTOR = com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_CONNECTOR
    const val RABBITMQ_CONNECTOR = com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_CONNECTOR
    const val PGMQ_CONNECTOR = com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_CONNECTOR

    const val COMMANDS_TOPIC_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.COMMANDS_TOPIC_DEFAULT
    const val EVENTS_TOPIC_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.EVENTS_TOPIC_DEFAULT
    const val CLOUDEVENTS_TOPIC_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.CLOUDEVENTS_TOPIC_DEFAULT
    const val LIFECYCLE_EVENTS_TOPIC_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.LIFECYCLE_EVENTS_TOPIC_DEFAULT

    const val POSTGRES_HOST_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_HOST_DEFAULT
    const val POSTGRES_PORT_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_PORT_DEFAULT
    const val POSTGRES_DATABASE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_DATABASE_DEFAULT
    const val POSTGRES_USERNAME_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_USERNAME_DEFAULT
    const val POSTGRES_PASSWORD_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.POSTGRES_PASSWORD_DEFAULT
    const val ANALYTICS_POSTGRES_DATABASE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_DATABASE_DEFAULT
    const val ANALYTICS_POSTGRES_SCHEMA_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_SCHEMA_DEFAULT
    const val ANALYTICS_POSTGRES_TABLE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_TABLE_DEFAULT
    const val ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_MIGRATE_AT_START_DEFAULT
    const val ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.ANALYTICS_POSTGRES_BASELINE_ON_MIGRATE_DEFAULT

    const val MYSQL_HOST_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_HOST_DEFAULT
    const val MYSQL_PORT_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_PORT_DEFAULT
    const val MYSQL_DATABASE_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_DATABASE_DEFAULT
    const val MYSQL_USERNAME_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_USERNAME_DEFAULT
    const val MYSQL_PASSWORD_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.MYSQL_PASSWORD_DEFAULT

    const val H2_DB_NAME_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.H2_DB_NAME_DEFAULT
    const val H2_USERNAME_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.H2_USERNAME_DEFAULT
    const val H2_PASSWORD_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.H2_PASSWORD_DEFAULT

    const val KAFKA_BROKERS_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_BROKERS_DEFAULT
    const val KAFKA_OFFSET_RESET_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_OFFSET_RESET_DEFAULT
    const val KAFKA_STRING_SERIALIZER =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_STRING_SERIALIZER
    const val KAFKA_STRING_DESERIALIZER =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_STRING_DESERIALIZER
    const val KAFKA_WORKFLOWS_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_WORKFLOWS_GROUP_ID_DEFAULT
    const val KAFKA_DATABASE_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_DATABASE_GROUP_ID_DEFAULT
    const val KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_CLOUDEVENTS_GROUP_ID_DEFAULT
    const val KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT

    const val RABBITMQ_HOST_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_HOST_DEFAULT
    const val RABBITMQ_PORT_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_PORT_DEFAULT
    const val RABBITMQ_USER_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_USER_DEFAULT
    const val RABBITMQ_PASSWORD_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_PASSWORD_DEFAULT
    const val RABBITMQ_VHOST_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_VHOST_DEFAULT
    const val RABBITMQ_STRING_SERIALIZER =
        com.lemline.runner.config.shared.LemlineConfigConstants.RABBITMQ_STRING_SERIALIZER

    const val METRICS_PORT_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.METRICS_PORT_DEFAULT
    const val METRICS_PATH_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.METRICS_PATH_DEFAULT

    const val GATEWAY_ENABLED_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_ENABLED_DEFAULT
    const val GATEWAY_GRPC_HOST_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_GRPC_HOST_DEFAULT
    const val GATEWAY_GRPC_PORT_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_GRPC_PORT_DEFAULT
    const val GATEWAY_TLS_ENABLED_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_TLS_ENABLED_DEFAULT
    const val GATEWAY_TLS_CLIENT_AUTH_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_TLS_CLIENT_AUTH_DEFAULT
    const val GATEWAY_AUTHENTICATION_ENABLED_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_AUTHENTICATION_ENABLED_DEFAULT
    const val GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_AUTHENTICATION_SCOPE_FIELD_DEFAULT
    const val GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_AUTHENTICATION_NAMESPACES_FIELD_DEFAULT
    const val GATEWAY_CORS_ENABLED_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_ENABLED_DEFAULT
    const val GATEWAY_CORS_ORIGINS_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_ORIGINS_DEFAULT
    const val GATEWAY_CORS_METHODS_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_METHODS_DEFAULT
    const val GATEWAY_CORS_HEADERS_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_CORS_HEADERS_DEFAULT
    const val GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_WATCH_POLL_INTERVAL_MS_DEFAULT
    const val GATEWAY_WATCH_BATCH_SIZE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.GATEWAY_WATCH_BATCH_SIZE_DEFAULT

    const val PGMQ_VISIBILITY_TIMEOUT_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT
    const val PGMQ_POLL_INTERVAL_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT
    const val PGMQ_BATCH_SIZE_DEFAULT = com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT
    const val PGMQ_MAX_POOL_SIZE_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_MAX_POOL_SIZE_DEFAULT
    const val PGMQ_MAX_RETRIES_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT
    const val PGMQ_COMMANDS_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_COMMANDS_GROUP_ID_DEFAULT
    const val PGMQ_EVENTS_GROUP_ID_DEFAULT =
        com.lemline.runner.config.shared.LemlineConfigConstants.PGMQ_EVENTS_GROUP_ID_DEFAULT
}

const val DATABASE_TYPE = com.lemline.runner.config.shared.DATABASE_TYPE
const val MIGRATE_AT_START = com.lemline.runner.config.shared.MIGRATE_AT_START
const val MESSAGING_TYPE = com.lemline.runner.config.shared.MESSAGING_TYPE

const val COMMANDS_PRODUCER_ENABLED = com.lemline.runner.config.shared.COMMANDS_PRODUCER_ENABLED
const val COMMANDS_CONSUMER_ENABLED = com.lemline.runner.config.shared.COMMANDS_CONSUMER_ENABLED
const val COMMANDS_CONSUMER_CONCURRENCY = com.lemline.runner.config.shared.COMMANDS_CONSUMER_CONCURRENCY

const val EVENTS_PRODUCER_ENABLED = com.lemline.runner.config.shared.EVENTS_PRODUCER_ENABLED
const val EVENTS_CONSUMER_ENABLED = com.lemline.runner.config.shared.EVENTS_CONSUMER_ENABLED
const val EVENTS_CONSUMER_CONCURRENCY = com.lemline.runner.config.shared.EVENTS_CONSUMER_CONCURRENCY

const val CLOUDEVENTS_PRODUCER_ENABLED = com.lemline.runner.config.shared.CLOUDEVENTS_PRODUCER_ENABLED
const val CLOUDEVENTS_CONSUMER_ENABLED = com.lemline.runner.config.shared.CLOUDEVENTS_CONSUMER_ENABLED
const val CLOUDEVENTS_CONSUMER_CONCURRENCY = com.lemline.runner.config.shared.CLOUDEVENTS_CONSUMER_CONCURRENCY

const val LIFECYCLE_EVENTS_PRODUCER_ENABLED = com.lemline.runner.config.shared.LIFECYCLE_EVENTS_PRODUCER_ENABLED
const val LIFECYCLE_EVENTS_CONSUMER_ENABLED = com.lemline.runner.config.shared.LIFECYCLE_EVENTS_CONSUMER_ENABLED
const val LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY =
    com.lemline.runner.config.shared.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY

const val DATABASE_ENABLED = com.lemline.runner.config.shared.DATABASE_ENABLED
const val SCHEDULED_ENABLED = com.lemline.runner.config.shared.SCHEDULED_ENABLED

const val ORCHESTRATOR_MODE = com.lemline.runner.config.shared.ORCHESTRATOR_MODE
