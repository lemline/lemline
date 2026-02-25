// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.cli.config.ConfigPathHolder
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME
import com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.ANALYTICS_TYPE_H2
import com.lemline.runner.common.config.ANALYTICS_TYPE_POSTGRESQL
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.messaging.LIFECYCLEEVENTS_IN_CHANNEL
import com.lemline.runner.config.LemlineConfigConstants.KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_BATCH_SIZE_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_MAX_RETRIES_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_POLL_INTERVAL_DEFAULT
import com.lemline.runner.config.LemlineConfigConstants.PGMQ_VISIBILITY_TIMEOUT_DEFAULT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LemlineConfigSourceLifecycleAnalyticsTest {

    @Test
    fun `generates kafka lifecycleevents analytics consumer properties`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "kafka",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "17",
                LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC to "lemline-lifecycle-events-custom"
            )
        ) { source ->
            val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
            assertEquals("smallrye-kafka", source.getValue("$incoming.connector"))
            assertEquals("lemline-lifecycle-events-custom", source.getValue("$incoming.topic"))
            assertEquals("true", source.getValue("$incoming.broadcast"))
            assertEquals(KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT, source.getValue("$incoming.group.id"))
            assertEquals("17", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `kafka lifecycleevents backend-specific concurrency overrides generic value`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "kafka",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "17",
                LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "29"
            )
        ) { source ->
            assertEquals("29", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `generates rabbit lifecycleevents analytics consumer properties`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "rabbitmq",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "19",
                LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-events-custom",
                LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME to "lemline-lifecycle-events-exchange"
            )
        ) { source ->
            val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
            assertEquals("smallrye-rabbitmq", source.getValue("$incoming.connector"))
            assertEquals("lemline-lifecycle-events-custom", source.getValue("$incoming.queue.name"))
            assertEquals("true", source.getValue("$incoming.broadcast"))
            assertEquals("lemline-lifecycle-events-exchange", source.getValue("$incoming.exchange.name"))
            assertEquals("reject", source.getValue("$incoming.failure-strategy"))
            assertEquals("19", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `rabbit lifecycleevents backend-specific concurrency overrides generic value`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "rabbitmq",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "19",
                LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "31"
            )
        ) { source ->
            assertEquals("31", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `generates pgmq lifecycleevents analytics consumer properties`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "pgmq",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "23",
                LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-events-custom"
            )
        ) { source ->
            val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
            assertEquals("smallrye-pgmq", source.getValue("$incoming.connector"))
            assertEquals("lemline-lifecycle-events-custom", source.getValue("$incoming.queue"))
            assertEquals("true", source.getValue("$incoming.broadcast"))
            assertEquals(PGMQ_VISIBILITY_TIMEOUT_DEFAULT, source.getValue("$incoming.visibility-timeout"))
            assertEquals(PGMQ_POLL_INTERVAL_DEFAULT, source.getValue("$incoming.poll-interval"))
            assertEquals(PGMQ_BATCH_SIZE_DEFAULT, source.getValue("$incoming.batch-size"))
            assertEquals(PGMQ_MAX_RETRIES_DEFAULT, source.getValue("$incoming.max-retries"))
            assertEquals("23", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `pgmq lifecycleevents backend-specific concurrency overrides generic value`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "pgmq",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "23",
                LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "37"
            )
        ) { source ->
            assertEquals("37", source.getValue(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `generates analytics datasource properties only when enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "in-memory",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
                LEMLINE_ANALYTICS_POSTGRES_PORT to "5544",
                LEMLINE_ANALYTICS_POSTGRES_DATABASE to "analytics",
                LEMLINE_ANALYTICS_POSTGRES_USERNAME to "analytics_user",
                LEMLINE_ANALYTICS_POSTGRES_PASSWORD to "analytics_pass",
                LEMLINE_ANALYTICS_MIGRATE_AT_START to "false",
                LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "true"
            )
        ) { source ->
            assertEquals(ANALYTICS_TYPE_POSTGRESQL, source.getValue(LEMLINE_ANALYTICS_TYPE))
            assertEquals("analytics_user", source.getValue("quarkus.datasource.analytics-postgresql.username"))
            assertEquals("analytics_pass", source.getValue("quarkus.datasource.analytics-postgresql.password"))
            assertEquals(
                "jdbc:postgresql://analytics-db:5544/analytics",
                source.getValue("quarkus.datasource.analytics-postgresql.jdbc.url")
            )
            assertEquals("true", source.getValue("quarkus.flyway.analytics-postgresql.baseline-on-migrate"))
            assertEquals(
                "smallrye-in-memory",
                source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.connector")
            )
            assertEquals(
                "true",
                source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.broadcast")
            )
            assertEquals(
                "classpath:db/migration/analytics/postgresql",
                source.getValue("quarkus.flyway.analytics-postgresql.locations")
            )
        }

        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "in-memory",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "false",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db"
            )
        ) { source ->
            assertNull(source.getValue(LEMLINE_ANALYTICS_TYPE))
            assertNull(source.getValue("quarkus.datasource.analytics-postgresql.jdbc.url"))
            assertNull(source.getValue("quarkus.flyway.analytics-postgresql.locations"))
            assertNull(source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.connector"))
            assertNull(source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.broadcast"))
        }
    }

    @Test
    fun `selects analytics h2 datasource by default when lifecycle consumer is enabled`() {
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "in-memory",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true"
            )
        ) { source ->
            assertEquals(ANALYTICS_TYPE_H2, source.getValue(LEMLINE_ANALYTICS_TYPE))
            assertNull(source.getValue("quarkus.datasource.analytics-postgresql.jdbc.url"))
        }
    }

    @Test
    fun `ignores legacy analytics migrate keys under postgresql`() {
        val legacyMigrateKey = "${LEMLINE_ANALYTICS_POSTGRES}.migrate-at-start"
        val legacyBaselineKey = "${LEMLINE_ANALYTICS_POSTGRES}.baseline-on-migrate"
        withSystemProperties(
            mapOf(
                LEMLINE_MESSAGING_TYPE to "in-memory",
                LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
                LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
                legacyMigrateKey to "false",
                legacyBaselineKey to "true"
            )
        ) { source ->
            assertEquals(ANALYTICS_TYPE_POSTGRESQL, source.getValue(LEMLINE_ANALYTICS_TYPE))
            val jdbcUrl = source.getValue("quarkus.datasource.analytics-postgresql.jdbc.url")
            assertTrue(
                jdbcUrl.startsWith("jdbc:postgresql://analytics-db:"),
                "Expected analytics JDBC URL to use overridden host, but was: $jdbcUrl"
            )
            assertNull(source.getValue("quarkus.flyway.analytics-postgresql.migrate-at-start"))
            assertEquals("false", source.getValue("quarkus.flyway.analytics-postgresql.baseline-on-migrate"))
        }
    }

    private fun withSystemProperties(
        overrides: Map<String, String>,
        block: (LemlineConfigSource) -> Unit
    ) {
        val previousLemlineValues = System.getProperties().stringPropertyNames()
            .filter { it.startsWith("lemline.") }
            .associateWith { System.getProperty(it) }
        val previousConfigPath = ConfigPathHolder.configPath

        try {
            ConfigPathHolder.configPath = null
            previousLemlineValues.keys.forEach { System.clearProperty(it) }
            overrides.forEach { (key, value) -> System.setProperty(key, value) }
            block(LemlineConfigSource())
        } finally {
            System.getProperties().stringPropertyNames()
                .filter { it.startsWith("lemline.") }
                .forEach { System.clearProperty(it) }

            previousLemlineValues.forEach { (key, previous) ->
                if (previous != null) {
                    System.setProperty(key, previous)
                }
            }
            ConfigPathHolder.configPath = previousConfigPath
        }
    }
}
