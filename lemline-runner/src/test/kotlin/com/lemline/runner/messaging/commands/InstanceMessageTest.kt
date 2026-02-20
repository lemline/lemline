// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.commands

import com.lemline.core.states.WorkflowState
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.common.messaging.InstanceMessageCodec
import com.lemline.runner.random.random
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class InstanceMessageTest {

    @Test
    fun `serialized payload should use protobuf transport encoding`() {
        val instanceMessage = InstanceMessage.random()
        val encoded = instanceMessage.toTransportPayload()

        Assertions.assertFalse(encoded.trim().startsWith("{"), "Transport payload should not be raw JSON")
        val decoded = InstanceMessageCodec.fromTransportPayloadAs<WorkflowState>(encoded)
        Assertions.assertEquals(instanceMessage, decoded)
    }

    @Test
    fun `should roundtrip through transport codec`() {
        // Given
        val original = InstanceMessage.random()

        // When
        val serialized = InstanceMessageCodec.toTransportPayload(original)
        val deserialized = InstanceMessageCodec.fromTransportPayloadAs<WorkflowState>(serialized)

        // When
        Assertions.assertEquals(original, deserialized)
    }
}
