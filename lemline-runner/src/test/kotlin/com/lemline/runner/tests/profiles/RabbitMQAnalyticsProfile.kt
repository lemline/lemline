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
            "lemline.database.type" to DatabaseType.H2.configValue,
            "lemline.messaging.type" to MessagingType.RABBITMQ.configValue,
            "lemline.messaging.commands.consumer.enabled" to "false",
            "lemline.messaging.commands.producer.enabled" to "false",
            "lemline.messaging.events.consumer.enabled" to "false",
            "lemline.messaging.events.producer.enabled" to "false",
            "lemline.messaging.cloudevents.consumer.enabled" to "false",
            "lemline.messaging.cloudevents.producer.enabled" to "false",
            "lemline.messaging.lifecycleevents.producer.enabled" to "true",
            "lemline.analytics.consumer.enabled" to "true",
            "lemline.analytics.consumer.concurrency" to "16",
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
