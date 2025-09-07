// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@ExperimentalTime
class WorkflowsSerializationTest {

    @Test
    fun `NodeStates serializes and deserializes`() {
        val states = NodeStates.random()

        val encoded = states.toJsonString()
        val decoded = NodeStates.fromJsonString(encoded)

        assertEquals(states, decoded)

    }
}
