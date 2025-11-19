// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
class ParentWaitingModelTest {

    @Test
    fun `ParentModel can be created and fields accessed`() {
        val model = ParentModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        assertNotNull(model.childId)
        assertNotNull(model.workflowInfo)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.childId, copy.childId)
    }

}
