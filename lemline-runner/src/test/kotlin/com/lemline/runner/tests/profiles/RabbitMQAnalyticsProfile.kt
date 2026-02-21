// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.config.CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.config.LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class RabbitMQAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            DATABASE_TYPE to DatabaseType.H2.configValue,
            MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,
            COMMANDS_CONSUMER_ENABLED to "false",
            COMMANDS_PRODUCER_ENABLED to "false",
            EVENTS_CONSUMER_ENABLED to "false",
            EVENTS_PRODUCER_ENABLED to "false",
            CLOUDEVENTS_CONSUMER_ENABLED to "false",
            CLOUDEVENTS_PRODUCER_ENABLED to "false",
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "16",
            "lemline.messaging.rabbitmq.lifecycleevents.queue" to "lemline-lifecycle-analytics-rabbitmq",
            "lemline.messaging.rabbitmq.lifecycleevents.producer.exchange-name" to "lemline-lifecycle-analytics-exchange",
            "mp.messaging.incoming.lifecycleevents-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.lifecycleevents-out.exchange.type" to "fanout",
            "lemline.analytics.migrate-at-start" to "true",
            "lemline.analytics.baseline-on-migrate" to "false",
            "lemline.outbox.enabled" to "false",
            "lemline.scheduled.enabled" to "false"
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
