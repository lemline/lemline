// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.kafka

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
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_NAMESPACES
import com.lemline.runner.common.config.LEMLINE_ORCHESTRATOR_MODE
import com.lemline.runner.common.config.LEMLINE_OUTBOX_ENABLED
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY
import com.lemline.runner.common.config.LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.profiles.AnalyticsH2TestProfileSupport
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for Kafka messaging with per-namespace destinations.
 *
 * The runner follows every namespace used by the testcase workflow fixtures
 * (`default`, `examples`, `samples`, `test`), so LemlineConfigSource generates
 * consumption of each `lemline-<namespace>-<type>` topic. Emissions route to the
 * emitting workflow's namespace topic, so loopback (in = out per namespace) is
 * inherent — no raw mp.messaging destination overrides are needed.
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - Kafka channels with per-namespace topics (natural loopback)
 * - Lifecycle events with loopback for test verification (single shared topic)
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class KafkaTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            // Messaging configuration
            LEMLINE_MESSAGING_TYPE to MessagingType.KAFKA.configValue,
            LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",
            LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED to "true",
            LEMLINE_ANALYTICS_MIGRATE_AT_START to "true",
            LEMLINE_ANALYTICS_BASELINE_ON_MIGRATE to "false",

            // Orchestrator mode: ALL generates more messages for thorough end-to-end testing
            LEMLINE_ORCHESTRATOR_MODE to "all",

            // Follow every namespace used by the testcase workflow fixtures so the
            // generated per-namespace topics cover all of them (natural loopback,
            // consumers start from earliest by default)
            LEMLINE_NAMESPACES to "default,examples,samples,test",

            // Lifecycle events loopback - same topic for producer and test listener
            "mp.messaging.incoming.lifecycleevents-in.topic" to "lemline-lifecycle",
            "mp.messaging.incoming.lifecycleevents-in.auto.offset.reset" to "earliest",
            "mp.messaging.outgoing.lifecycleevents-out.topic" to "lemline-lifecycle",
            "mp.messaging.outgoing.lifecycleevents-out.auto.offset.reset" to "earliest",

            "smallrye.messaging.kafka.topic.creation.enable" to "true",

            // Enable outbox schedulers for Wait/Retry/Schedule tests
            LEMLINE_OUTBOX_ENABLED to "true",
            // Fast polling for tests (no jitter - start immediately for deterministic testing)
            LEMLINE_OUTBOX_WAIT_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_WAIT_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_RETRY_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_SCHEDULE_OUTBOX_INITIAL_JITTER to "0s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_EVERY to "1s",
            LEMLINE_OUTBOX_LISTENER_OUTBOX_INITIAL_JITTER to "0s"
        ) + AnalyticsH2TestProfileSupport.overrides("lemline_analytics_kafka_testcase")
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(
            QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java)
        )
    }

    override fun tags(): Set<String> {
        return setOf("kafka-testcase")
    }
}
