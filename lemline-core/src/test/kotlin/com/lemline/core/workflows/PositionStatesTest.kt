// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.lemline.core.random.random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

@ExperimentalTime
class PositionStatesTest {

    @Test
    fun `PositionStates serializes and deserializes`() {
        repeat(50) {
            // Given
            val states = PositionStates.random()
            // When
            val encoded = states.toJsonString()
            val decoded = PositionStates.fromJsonString(encoded)
            // Then
            assertEquals(states, decoded)
        }
    }
}
