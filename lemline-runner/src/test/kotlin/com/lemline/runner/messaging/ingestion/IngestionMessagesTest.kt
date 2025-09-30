// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.ingestion

import com.lemline.core.json.LemlineJson
import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.ForkModel
import com.lemline.runner.models.IngestionModel
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
internal class IngestionMessagesTest {

    @Test
    fun `should be JSON serializable and deserializable for Parent model`() {
        // Given
        val model = ParentModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)

        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
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
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
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
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Retry model`() {
        val model = RetryModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
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
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }

    @Test
    fun `should be JSON serializable and deserializable for Fork model`() {
        // Given
        val model = ForkModel.random()
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val original = IngestionMessages(model)
        // When
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded]}""",
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
        val encoded = LemlineJson.encodeToString<IngestionModel>(model)
        val msg = InstanceMessage.random()
        // When
        val original = IngestionMessages(listOf(model), listOf(msg))
        val serialized = original.toJsonString()

        // a serial name "i"
        assertEquals(
            """{"t":"i","db":[$encoded],"msg":[${msg.toJsonString()}]}""",
            serialized,
        )

        val deserialized = IngestionMessage.fromJsonString(serialized)
        // Then
        assertEquals(original, deserialized)
    }
}
