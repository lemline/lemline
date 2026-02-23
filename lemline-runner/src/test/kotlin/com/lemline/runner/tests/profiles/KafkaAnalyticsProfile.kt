// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.AnalyticsPostgresTestResource
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class KafkaAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            "lemline.database.type" to DatabaseType.H2.configValue,
            "lemline.messaging.type" to MessagingType.KAFKA.configValue,
            "lemline.messaging.commands.consumer.enabled" to "false",
            "lemline.messaging.commands.producer.enabled" to "false",
            "lemline.messaging.events.consumer.enabled" to "false",
            "lemline.messaging.events.producer.enabled" to "false",
            "lemline.messaging.cloudevents.consumer.enabled" to "false",
            "lemline.messaging.cloudevents.producer.enabled" to "false",
            "lemline.messaging.lifecycleevents.producer.enabled" to "true",
            "lemline.analytics.consumer.enabled" to "true",
            "lemline.analytics.consumer.concurrency" to "16",
            "lemline.messaging.kafka.lifecycleevents.topic" to "lemline-lifecycle-analytics-kafka",
            "kafka.allow.auto.create.topics" to "true",
            "smallrye.messaging.kafka.topic.creation.enable" to "true",
            "lemline.analytics.migrate-at-start" to "true",
            "lemline.analytics.baseline-on-migrate" to "false",
            "lemline.outbox.enabled" to "false",
            "lemline.scheduled.enabled" to "false"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java),
            QuarkusTestProfile.TestResourceEntry(AnalyticsPostgresTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("analytics", MessagingType.KAFKA.configValue)
    }
}
