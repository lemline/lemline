// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.schedules

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime

@ExperimentalTime
class ScheduleOutBoxModelTest {

    @Test
    fun `ScheduleModel can be created and fields accessed`() {
        val model = ScheduleModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
    }
}
