// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.runner.models.nullable
import com.lemline.runner.random.random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
internal class CompleteMessageTest {

    @Test
    fun `CompletedMessage should be JSON serializable and deserializable`() {
        // Given
        val original = CompletedMessage.random()

        // When
        val serialized = original.toJsonString()

        // a serial name "c"
        val parentId = nullable(original.parentId)
        val output = nullable(original.output)
        assertEquals(
            with(original) {
                """{"t":"c","i":${workflowInfo.toJsonString()},"p":$parentId,"o":$output,"sa":$isScheduledAfter}"""
            },
            serialized,
        )

        val deserialized = DatabaseMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }
}
