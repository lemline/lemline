// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.runner.models

import com.lemline.runner.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
class RetryOutBoxModelTest {

    @Test
    fun `RetryOutboxModel can be created and fields accessed`() {
        val model = RetryOutboxModel.random()

        // Verify all fields are accessible
        assertNotNull(model.id)
        assertNotNull(model.instanceMessage)
        assertNotNull(model.scheduledFor)
        assertNotNull(model.errorReason)
        assertNotNull(model.errorClass)
        assertNotNull(model.errorStackTrace)
        assertEquals(model.scheduledFor, model.outboxScheduledFor)
        assertEquals(model.scheduledFor, model.outboxDelayedUntil)

        // Verify copy works
        val copy = model.copy()
        assertEquals(model.id, copy.id)
        assertEquals(model.scheduledFor, copy.scheduledFor)
        assertEquals(model.errorReason, copy.errorReason)
    }

}
