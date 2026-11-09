// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.rabbitmq

import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_NAMESPACES
import com.lemline.runner.common.config.MessagingType
import com.lemline.runner.tests.resources.RabbitMQTestResource
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.QuarkusTestProfile.TestResourceEntry

/**
 * Test profile for the RabbitMQ per-namespace topology bootstrap.
 *
 * Contrary to [com.lemline.runner.tests.profiles.RabbitMQProfile], this profile does NOT
 * override the pool queue names: it exercises the production naming path where
 * - producers publish to the channel exchange `lemline-commands` with routing key = namespace,
 * - the pool queue is derived from the followed namespaces (`lemline-commands.test-in`),
 * - [RabbitTopologyBootstrap] declares `lemline-test-in-commands` and its bindings.
 */
class RabbitTopologyBootstrapProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> = mapOf(
        // H2 (in-memory) database for persistence (failures storage is asserted on)
        LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,

        // RabbitMQ messaging, scoped to a single followed namespace
        LEMLINE_MESSAGING_TYPE to MessagingType.RABBITMQ.configValue,
        LEMLINE_NAMESPACES to "test-in",

        // Channel enabled flags: no queue-name overrides, production naming applies
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED to "true",
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED to "true",
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED to "true",
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED to "true",
    )

    override fun testResources(): List<TestResourceEntry> =
        listOf(TestResourceEntry(RabbitMQTestResource::class.java))

    override fun tags(): Set<String> = setOf(MessagingType.RABBITMQ.configValue)
}
