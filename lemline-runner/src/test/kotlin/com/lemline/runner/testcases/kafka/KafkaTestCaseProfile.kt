// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.testcases.kafka

import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import kotlin.time.ExperimentalTime
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.config.ORCHESTRATOR_MODE
import com.lemline.runner.testcases.TestLifecycleEventEmitter
import com.lemline.runner.tests.resources.KafkaTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for Kafka messaging with loopback configuration.
 *
 * Uses the SAME topic for commands-in and commands-out (and events-in/out)
 * to create a natural message loop for end-to-end workflow testing.
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - Kafka channels with loopback topics
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class KafkaTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            DATABASE_TYPE to LemlineConfigConstants.DB_TYPE_IN_MEMORY,
            // Messaging configuration
            MESSAGING_TYPE to LemlineConfigConstants.MSG_TYPE_KAFKA,
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",

            // Orchestrator mode: ALL generates more messages for thorough end-to-end testing
            ORCHESTRATOR_MODE to "all",

            // Use SAME topic for in/out to create loopback
            "mp.messaging.incoming.commands-in.topic" to "lemline-commands",
            "mp.messaging.outgoing.commands-out.topic" to "lemline-commands",
            "mp.messaging.incoming.events-in.topic" to "lemline-events",
            "mp.messaging.outgoing.events-out.topic" to "lemline-events",

            "smallrye.messaging.kafka.topic.creation.enable" to "true",

            // Enable outbox schedulers for Wait/Fork/Retry tests
            "lemline.outbox.enabled" to "true",
            // Fast polling for tests (1s interval, no initial delay)
            "lemline.outbox.wait.outbox.every" to "1s",
            "lemline.outbox.wait.outbox.initial-delay" to "1s",
            "lemline.outbox.retry.outbox.every" to "1s",
            "lemline.outbox.retry.outbox.initial-delay" to "1s",
            "lemline.outbox.schedule.outbox.every" to "1s",
            "lemline.outbox.schedule.outbox.initial-delay" to "1s"
        )
    }

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> {
        return listOf(QuarkusTestProfile.TestResourceEntry(KafkaTestResource::class.java))
    }

    override fun tags(): Set<String> {
        return setOf("kafka-testcase")
    }

    /**
     * Enables the test lifecycle event emitter alternative.
     * This allows tests to capture CloudEvents for verification.
     */
    override fun getEnabledAlternatives(): Set<Class<*>> {
        return setOf(TestLifecycleEventEmitter::class.java)
    }
}
