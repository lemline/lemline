// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.cli.config.ConfigPathHolder
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
                com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "kafka",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_CONCURRENCY to "17",
                com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC to "lemline-lifecycle-events-custom"
            )
        ) { source ->
            val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
            assertEquals("smallrye-kafka", source.getValue("$incoming.connector"))
            assertEquals("lemline-lifecycle-events-custom", source.getValue("$incoming.topic"))
            assertEquals("true", source.getValue("$incoming.broadcast"))
            assertEquals(KAFKA_LIFECYCLE_EVENTS_GROUP_ID_DEFAULT, source.getValue("$incoming.group.id"))
            assertEquals("17", source.getValue(com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_CONCURRENCY))
        }
    }

    @Test
    fun `generates rabbit lifecycleevents analytics consumer properties`() {
        withSystemProperties(
            mapOf(
                com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "rabbitmq",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
                com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-events-custom",
                com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME to "lemline-lifecycle-events-exchange"
            )
        ) { source ->
            val incoming = "mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL"
            assertEquals("smallrye-rabbitmq", source.getValue("$incoming.connector"))
            assertEquals("lemline-lifecycle-events-custom", source.getValue("$incoming.queue.name"))
            assertEquals("true", source.getValue("$incoming.broadcast"))
            assertEquals("lemline-lifecycle-events-exchange", source.getValue("$incoming.exchange.name"))
            assertEquals("reject", source.getValue("$incoming.failure-strategy"))
        }
    }

    @Test
    fun `generates pgmq lifecycleevents analytics consumer properties`() {
        withSystemProperties(
            mapOf(
                com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "pgmq",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
                com.lemline.runner.common.config.LEMLINE_MESSAGING_PGMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-events-custom"
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
        }
    }

    @Test
    fun `generates analytics datasource properties only when enabled`() {
        withSystemProperties(
            mapOf(
                com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "in-memory",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PORT to "5544",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_DATABASE to "analytics",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_USERNAME to "analytics_user",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_PASSWORD to "analytics_pass",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START to "false",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "true"
            )
        ) { source ->
            assertEquals("postgresql", source.getValue("quarkus.datasource.analytics.db-kind"))
            assertEquals("analytics_user", source.getValue("quarkus.datasource.analytics.username"))
            assertEquals("analytics_pass", source.getValue("quarkus.datasource.analytics.password"))
            assertEquals(
                "jdbc:postgresql://analytics-db:5544/analytics",
                source.getValue("quarkus.datasource.analytics.jdbc.url")
            )
            assertEquals("false", source.getValue("quarkus.flyway.analytics.migrate-at-start"))
            assertEquals("true", source.getValue("quarkus.flyway.analytics.baseline-on-migrate"))
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
                source.getValue("quarkus.flyway.analytics.locations")
            )
        }

        withSystemProperties(
            mapOf(
                com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "in-memory",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "false",
                com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db"
            )
        ) { source ->
            assertNull(source.getValue("quarkus.datasource.analytics.jdbc.url"))
            assertNull(source.getValue("quarkus.flyway.analytics.locations"))
            assertNull(source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.connector"))
            assertNull(source.getValue("mp.messaging.incoming.$LIFECYCLEEVENTS_IN_CHANNEL.broadcast"))
        }
    }

    @Test
    fun `supports legacy analytics migrate keys under postgresql`() {
        val newMigrateKey = com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
        val newBaselineKey = com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
        val legacyMigrateKey = "${com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES}.migrate-at-start"
        val legacyBaselineKey = "${com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES}.baseline-on-migrate"
        val previousNewMigrate = System.getProperty(newMigrateKey)
        val previousNewBaseline = System.getProperty(newBaselineKey)

        try {
            System.clearProperty(newMigrateKey)
            System.clearProperty(newBaselineKey)

            withSystemProperties(
                mapOf(
                    com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to "in-memory",
                    com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
                    com.lemline.runner.common.config.LEMLINE_ANALYTICS_POSTGRES_HOST to "analytics-db",
                    legacyMigrateKey to "false",
                    legacyBaselineKey to "true"
                )
            ) { source ->
                val jdbcUrl = source.getValue("quarkus.datasource.analytics.jdbc.url")
                assertTrue(
                    jdbcUrl.startsWith("jdbc:postgresql://analytics-db:"),
                    "Expected analytics JDBC URL to use overridden host, but was: $jdbcUrl"
                )
                assertEquals("false", source.getValue("quarkus.flyway.analytics.migrate-at-start"))
                assertEquals("true", source.getValue("quarkus.flyway.analytics.baseline-on-migrate"))
            }
        } finally {
            if (previousNewMigrate == null) {
                System.clearProperty(newMigrateKey)
            } else {
                System.setProperty(newMigrateKey, previousNewMigrate)
            }
            if (previousNewBaseline == null) {
                System.clearProperty(newBaselineKey)
            } else {
                System.setProperty(newBaselineKey, previousNewBaseline)
            }
        }
    }

    private fun withSystemProperties(
        overrides: Map<String, String>,
        block: (LemlineConfigSource) -> Unit
    ) {
        val previousValues = overrides.mapValues { System.getProperty(it.key) }
        val previousConfigPath = ConfigPathHolder.configPath

        try {
            ConfigPathHolder.configPath = null
            overrides.forEach { (key, value) -> System.setProperty(key, value) }
            block(LemlineConfigSource())
        } finally {
            overrides.keys.forEach { key ->
                val previous = previousValues[key]
                if (previous == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, previous)
                }
            }
            ConfigPathHolder.configPath = previousConfigPath
        }
    }
}
