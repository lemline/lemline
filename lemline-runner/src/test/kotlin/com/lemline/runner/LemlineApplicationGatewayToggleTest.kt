// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.common.config.LEMLINE_DATABASE_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_SCHEDULED_ENABLED
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LemlineApplicationGatewayToggleTest {

    private val enableGateway = Class.forName("com.lemline.runner.LemlineApplicationKt")
        .getDeclaredMethod("enableGateway")
        .apply { isAccessible = true }

    private val propertyKeys = listOf(
        LEMLINE_GATEWAY_ENABLED,
        LEMLINE_DATABASE_ENABLED,
        LEMLINE_SCHEDULED_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED,
        "quarkus.smallrye-health.enabled"
    )

    @AfterEach
    fun clearProperties() {
        propertyKeys.forEach(System::clearProperty)
    }

    @Test
    fun `enableGateway enables gateway mode and health endpoint`() {
        enableGateway.invoke(null)

        assertEquals("true", System.getProperty(LEMLINE_GATEWAY_ENABLED))
        assertEquals("true", System.getProperty(LEMLINE_DATABASE_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_SCHEDULED_ENABLED))
        assertEquals("true", System.getProperty("quarkus.smallrye-health.enabled"))
        assertEquals("true", System.getProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED))
        assertEquals("false", System.getProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED))
    }
}
