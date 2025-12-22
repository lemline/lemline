// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.retries

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalTime
@ExperimentalSerializationApi
class RetryModelTest {

    @Test
    fun `RetryModel can be created and fields accessed`() {
        val model = RetryModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        assertNotNull(model.outboxScheduledFor)
        assertNotNull(model.errorReason)
        assertNotNull(model.errorClass)
        assertNotNull(model.errorStackTrace)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.outboxScheduledFor, copy.outboxScheduledFor)
    }
}
