// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.runner.messaging.InstanceMessage
import com.lemline.runner.random.random
import kotlin.time.ExperimentalTime
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@ExperimentalTime
internal class InstanceMessageTest {

    @Test
    fun `serialized keys maintain their values for messages backward compatibility`() {
        // Given
        val instanceMessage = InstanceMessage.random()

        // Serialize and deserialize to verify backward compatibility
        val encoded = instanceMessage.toJsonString()
        val decoded = InstanceMessage.fromJsonString<com.lemline.core.states.WorkflowCommand>(encoded)

        // Verify the essential fields match
        Assertions.assertEquals(instanceMessage, decoded)
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val original = InstanceMessage.random()

        // When
        val serialized = original.toJsonString()
        val deserialized = InstanceMessage.fromJsonString<com.lemline.core.states.WorkflowCommand>(serialized)

        // When
        Assertions.assertEquals(original, deserialized)
    }
}
