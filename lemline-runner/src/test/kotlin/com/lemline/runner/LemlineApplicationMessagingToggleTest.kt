// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED
import com.lemline.runner.common.config.LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LemlineApplicationMessagingToggleTest {

    private val disableMessaging = Class.forName("com.lemline.runner.LemlineApplicationKt")
        .getDeclaredMethod("disableMessaging")
        .apply { isAccessible = true }

    private val messagingPropertyKeys = listOf(
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED
    )

    @AfterEach
    fun clearProperties() {
        messagingPropertyKeys.forEach(System::clearProperty)
    }

    @Test
    fun `disableMessaging disables every messaging channel toggle`() {
        messagingPropertyKeys.forEach { key -> System.setProperty(key, "true") }

        disableMessaging.invoke(null)

        messagingPropertyKeys.forEach { key ->
            assertEquals("false", System.getProperty(key), "Expected '$key' to be forced to false")
        }
    }
}
