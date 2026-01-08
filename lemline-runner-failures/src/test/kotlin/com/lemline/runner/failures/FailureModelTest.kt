// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.failures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
class FailureModelTest {

    @Test
    fun `FailureModel can be created and fields accessed`() {
        val model = FailureModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.errorReason)
        assertNotNull(model.errorClass)
        assertNotNull(model.errorStackTrace)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.errorReason, copy.errorReason)
    }
}
