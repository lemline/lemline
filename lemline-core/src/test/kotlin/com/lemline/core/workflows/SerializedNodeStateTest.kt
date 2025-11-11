// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.common.json.LemlineJson
import com.lemline.core.set
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@ExperimentalTime
class SerializedNodeStateTest {
    private val jsonFactory = JsonNodeFactory.instance

    @Test
    fun `test constants must not change to maintain expected behavior`() {
        assertEquals(-1, SerializedNodeState.CHILD_INDEX_DEFAULT)
        assertEquals(0, SerializedNodeState.ATTEMPT_INDEX_DEFAULT)
        assertEquals(-1, SerializedNodeState.FOR_INDEX_DEFAULT)
    }

    @Test
    fun `test constants maintain their values for messages backward compatibility`() {
        assertEquals("idx", SerializedNodeState.CHILD_INDEX)
        assertEquals("try", SerializedNodeState.ATTEMPT_INDEX)
        assertEquals("inp", SerializedNodeState.RAW_INPUT)
        assertEquals("out", SerializedNodeState.RAW_OUTPUT)
        assertEquals("ctx", SerializedNodeState.CONTEXT)
        assertEquals("wid", SerializedNodeState.WORKFLOW_ID)
        assertEquals("sat", SerializedNodeState.STARTED_AT)
        assertEquals("for", SerializedNodeState.FOR_INDEX)
        assertEquals("wai", SerializedNodeState.IS_WAITING)
    }

    @Test
    fun `test default values`() {
        val state = SerializedNodeState()
        assertEquals(SerializedNodeState.ATTEMPT_INDEX_DEFAULT, state.attemptIndex)
        assertEquals(SerializedNodeState.CHILD_INDEX_DEFAULT, state.childIndex)
        assertNull(state.rawInput)
        assertNull(state.rawOutput)
        assertTrue(state.context.isEmpty())
        assertNull(state.startedAt)
        assertEquals(SerializedNodeState.FOR_INDEX_DEFAULT, state.forIndex)
    }

    @Nested
    inner class SerializationTests {
        private lateinit var state: SerializedNodeState
        private val testInstant = Instant.parse("2024-01-01T00:00:00Z")

        @BeforeEach
        fun setup() {
            state = SerializedNodeState().apply {
                attemptIndex = 1
                childIndex = 2
                rawInput = LemlineJson.jsonObject.set("input", "test")
                rawOutput = LemlineJson.jsonObject.set("output", "result")
                context = LemlineJson.jsonObject.set("contextKey", "contextValue")
                startedAt = testInstant
                forIndex = 3
            }
        }

        @Nested
        inner class ErrorHandlingTests {
            private val invalidJson = jsonFactory.objectNode()

            @BeforeEach
            fun setup() {
                invalidJson.put(SerializedNodeState.ATTEMPT_INDEX, "not-an-int")
                invalidJson.put(SerializedNodeState.CHILD_INDEX, "not-an-int")
                invalidJson.put(SerializedNodeState.CONTEXT, "not-an-object")
                invalidJson.put(SerializedNodeState.STARTED_AT, "not-a-date")
                invalidJson.put(SerializedNodeState.FOR_INDEX, "not-an-int")
            }

            @Test
            fun `test invalid attempt index type`() {
                val json = LemlineJson.jsonObject.set(SerializedNodeState.ATTEMPT_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<SerializedNodeState>(json)
                }
            }

            @Test
            fun `test invalid child index type`() {
                val json = LemlineJson.jsonObject.set(SerializedNodeState.CHILD_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<SerializedNodeState>(json)
                }
            }

            @Test
            fun `test invalid context type`() {
                val json = LemlineJson.jsonObject.set(SerializedNodeState.CONTEXT, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<SerializedNodeState>(json)
                }
            }

            @Test
            fun `test invalid started at format`() {
                val json = LemlineJson.jsonObject.set(SerializedNodeState.STARTED_AT, "invalid-date")
                assertThrows(Exception::class.java) {
                    LemlineJson.decodeFromElement<SerializedNodeState>(json)
                }
            }

            @Test
            fun `test invalid for index type`() {
                val json = LemlineJson.jsonObject.set(SerializedNodeState.FOR_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<SerializedNodeState>(json)
                }
            }
        }

        @Test
        fun `default values should not appear during serialization`() {
            val state = SerializedNodeState().apply {
                childIndex = 1
            }

            assertEquals(
                """{"idx":1}""",
                LemlineJson.encodeToString(state),
            )
        }
    }
}
