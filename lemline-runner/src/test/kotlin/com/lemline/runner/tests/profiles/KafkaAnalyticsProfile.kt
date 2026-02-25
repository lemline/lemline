// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_MIGRATE_AT_START
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED
import com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile

class KafkaAnalyticsProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            LEMLINE_MESSAGING_TYPE to MessagingType.KAFKA.configValue,
            LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "false",
            LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "false",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_CONCURRENCY to "16",
            LEMLINE_MESSAGING_KAFKA_LIFECYCLE_EVENTS_TOPIC to "lemline-lifecycle-analytics-kafka",
            "kafka.allow.auto.create.topics" to "true",
            "smallrye.messaging.kafka.topic.creation.enable" to "true",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",
            LEMLINE_OUTBOX_ENABLED to "false",
            LEMLINE_SCHEDULED_ENABLED to "false"
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_kafka")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("analytics", MessagingType.KAFKA.configValue)
    }
}
