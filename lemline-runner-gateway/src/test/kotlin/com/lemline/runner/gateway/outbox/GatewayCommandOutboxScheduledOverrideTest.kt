// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.outbox

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GatewayCommandOutboxScheduledOverrideTest {

    @Test
    fun `gateway command outbox can run when global scheduling is disabled`() {
        val getter = GatewayCommandOutbox::class.java.getDeclaredMethod("getRunWhenScheduledDisabled")
        getter.isAccessible = true

        assertTrue(getter.invoke(GatewayCommandOutbox()) as Boolean)
    }

    @Test
    fun `gateway command outbox cleaner can run when global scheduling is disabled`() {
        val getter = GatewayCommandOutboxCleaner::class.java.getDeclaredMethod("getRunWhenScheduledDisabled")
        getter.isAccessible = true

        assertTrue(getter.invoke(GatewayCommandOutboxCleaner()) as Boolean)
    }
}
