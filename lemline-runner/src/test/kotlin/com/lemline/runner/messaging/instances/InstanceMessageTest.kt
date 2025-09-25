// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.instances

import com.lemline.common.json.LemlineJson
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

        // When
        Assertions.assertEquals(
            """{"i":${instanceMessage.workflowInfo.toJsonString()},"s":${instanceMessage.workflowState.toJsonString()},"p":"${instanceMessage.parentId}"}""",
            instanceMessage.toJsonString(),
        )
    }

    @Test
    fun `should be JSON serializable and deserializable`() {
        // Given
        val original = InstanceMessage.random()

        // When
        val serialized = LemlineJson.encodeToString(original)
        val deserialized = InstanceMessage.fromJsonString(serialized)

        // When
        Assertions.assertEquals(original, deserialized)
    }
}
