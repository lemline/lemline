// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class MessageSubscriberMetricsTest {
    @Test
    fun `subscriber metrics use distinct prefixes from construction onward`() = runBlocking {
        val registry = SimpleMeterRegistry()
        try {
            val commands = object : MessageSubscriberMetrics(registry, "commands") {}
            val events = object : MessageSubscriberMetrics(registry, "events") {}

            commands.incrementActive()
            events.incrementActive()
            events.incrementActive()
            commands.received()
            events.received()
            events.received()
            commands.recordProcessingDuration(null) { Unit }
            events.recordProcessingDuration(null) { Unit }

            assertEquals(1.0, registry.get("commands.active").gauge().value())
            assertEquals(2.0, registry.get("events.active").gauge().value())
            assertEquals(1.0, registry.get("commands.received.total").counter().count())
            assertEquals(2.0, registry.get("events.received.total").counter().count())
            for (prefix in listOf("commands", "events")) {
                assertEquals(1L, registry.get("$prefix.processing.duration").timer().count())
                assertEquals(1.0, registry.get("$prefix.processing.completed.total").counter().count())
            }
            commands.decrementActive()
            assertEquals(0.0, registry.get("commands.active").gauge().value())
            assertEquals(2.0, registry.get("events.active").gauge().value())
            assertFalse(registry.meters.any { it.id.name.startsWith("null.") })
        } finally {
            registry.close()
        }
    }
}
