// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class RabbitMQAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_ENABLED to "true",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_CONSUMER_CONCURRENCY to "16",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_QUEUE to "lemline-lifecycle-analytics-rabbitmq",
            com.lemline.runner.common.config.LEMLINE_MESSAGING_RABBITMQ_LIFECYCLE_EVENTS_PRODUCER_EXCHANGE_NAME to "lemline-lifecycle-analytics-exchange",
            "mp.messaging.incoming.lifecycleevents-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.lifecycleevents-out.exchange.type" to "fanout",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
            com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED to "false",
            com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED to "false"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(RabbitMQTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("analytics", MessagingType.RABBITMQ.configValue)
    }
}
