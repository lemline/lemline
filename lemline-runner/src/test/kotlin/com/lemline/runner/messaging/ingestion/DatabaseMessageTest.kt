// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.ingestion

import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ParentModel
import com.lemline.runner.models.RetryModel
import com.lemline.runner.models.ScheduleModel
import com.lemline.runner.random.random
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@ExperimentalTime
@ExperimentalSerializationApi
internal class DatabaseMessageTest {

    @Test
    fun `should be JSON serializable and deserializable for Parent model`() {
        // Given
        val model = ParentModel.random()
        val original = IngestionMessages(model)

        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Wait model`() {
        // Given
        val model = ParentModel.random()
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Schedule model`() {
        // Given
        val model = ScheduleModel.random()
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry model`() {
        val model = RetryModel.random()
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Failure model`() {
        // Given
        val model = FailureModel.random()
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Instance message`() {
        // Given
        val model = FailureModel.random()
        val msg = InstanceMessage.random()
        val original = IngestionMessages(listOf(model), listOf(msg))
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[${model.toJsonString()}],"msg":[${msg.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }
}
