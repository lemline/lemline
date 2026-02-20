// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.waits

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WaitModelTest {

    @Test
    fun `WaitModel can be created and fields accessed`() {
        val model = WaitModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        assertNotNull(model.outboxScheduledFor)
        assertEquals(model.outboxScheduledFor, model.outboxScheduledFor)
        assertEquals(model.outboxScheduledFor, model.outboxDelayedUntil)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.outboxScheduledFor, copy.outboxScheduledFor)
    }
}
