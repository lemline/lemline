// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.testcases.rabbitmq

import com.lemline.runner.config.COMMANDS_CONSUMER_ENABLED
import kotlin.time.ExperimentalTime
import com.lemline.runner.config.COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.config.DATABASE_TYPE
import com.lemline.runner.config.EVENTS_CONSUMER_ENABLED
import com.lemline.runner.config.EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.config.LemlineConfigConstants
import com.lemline.runner.config.MESSAGING_TYPE
import com.lemline.runner.config.ORCHESTRATOR_MODE
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for RabbitMQ messaging with loopback configuration.
 *
 * Uses a shared exchange to create a loopback where messages produced to
 * commands-out are routed back to commands-in (and similarly for events).
 *
 * This profile configures:
 * - H2 (in-memory) database for persistence
 * - RabbitMQ channels with loopback via shared exchanges
 * - Lifecycle events with loopback for test verification
 * - Outbox schedulers enabled with fast polling for Wait/Fork/Retry tests
 */
class RabbitMQTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> {
        return mapOf(
            // Database configuration
            DATABASE_TYPE to LemlineConfigConstants.DB_TYPE_IN_MEMORY,
            // Messaging configuration
            MESSAGING_TYPE to LemlineConfigConstants.MSG_TYPE_RABBITMQ,
            COMMANDS_CONSUMER_ENABLED to "true",
            COMMANDS_PRODUCER_ENABLED to "true",
            EVENTS_CONSUMER_ENABLED to "true",
            EVENTS_PRODUCER_ENABLED to "true",

            // Enable lifecycle events producer so events flow through the broker
            LIFECYCLE_EVENTS_PRODUCER_ENABLED to "true",

            // Orchestrator mode: ALL generates more messages for thorough end-to-end testing
            ORCHESTRATOR_MODE to "all",

            // Loopback configuration using shared exchanges
            // Commands: input queue binds to the same exchange that output publishes to
            "mp.messaging.incoming.commands-in.queue.name" to "lemline-commands",
            "mp.messaging.incoming.commands-in.exchange.name" to "lemline-commands-exchange",
            "mp.messaging.incoming.commands-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.commands-out.exchange.name" to "lemline-commands-exchange",
            "mp.messaging.outgoing.commands-out.exchange.type" to "fanout",

            // Events: input queue binds to the same exchange that output publishes to
            "mp.messaging.incoming.events-in.queue.name" to "lemline-events",
            "mp.messaging.incoming.events-in.exchange.name" to "lemline-events-exchange",
            "mp.messaging.incoming.events-in.exchange.type" to "fanout",
            "mp.messaging.outgoing.events-out.exchange.name" to "lemline-events-exchange",
            "mp.messaging.outgoing.events-out.exchange.type" to "fanout",

            // Lifecycle events loopback - same exchange for producer and test listener
            "mp.messaging.outgoing.lifecycleevents-out.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.outgoing.lifecycleevents-out.exchange.type" to "fanout",
            "mp.messaging.incoming.lifecycleevents-in.connector" to "smallrye-rabbitmq",
            "mp.messaging.incoming.lifecycleevents-in.queue.name" to "lemline-lifecycle-test",
            "mp.messaging.incoming.lifecycleevents-in.exchange.name" to "lemline-lifecycle-exchange",
            "mp.messaging.incoming.lifecycleevents-in.exchange.type" to "fanout",

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
        return listOf(QuarkusTestProfile.TestResourceEntry(RabbitMQTestResource::class.java))
    }

    override fun tags(): Set<String> {
        return setOf("rabbitmq-testcase")
    }
}
