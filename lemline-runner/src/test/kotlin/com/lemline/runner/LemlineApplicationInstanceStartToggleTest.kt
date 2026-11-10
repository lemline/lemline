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

class LemlineApplicationInstanceStartToggleTest {

    private val applicationKt = Class.forName("com.lemline.runner.LemlineApplicationKt")

    private val disableMessaging = applicationKt.getDeclaredMethod("disableMessaging")
        .apply { isAccessible = true }

    private val enableInstanceStart = applicationKt.getDeclaredMethod("enableInstanceStart")
        .apply { isAccessible = true }

    // Every key disableMessaging sets, so nothing leaks into other tests of the same JVM
    private val propertyKeys = listOf(
        LEMLINE_MESSAGING_COMMANDS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_EVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_CLOUDEVENTS_PRODUCER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_CONSUMER_ENABLED,
        LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED,
    )

    @AfterEach
    fun clearProperties() {
        propertyKeys.forEach(System::clearProperty)
    }

    @Test
    fun `enableInstanceStart re-enables commands and lifecycle producers after disableMessaging`() {
        // Same order as the CLI bootstrap: non-listen commands first disable all messaging
        disableMessaging.invoke(null)
        enableInstanceStart.invoke(null)

        assertEquals("true", System.getProperty(LEMLINE_MESSAGING_COMMANDS_PRODUCER_ENABLED))
        assertEquals("true", System.getProperty(LEMLINE_MESSAGING_LIFECYCLE_EVENTS_PRODUCER_ENABLED))
    }
}
