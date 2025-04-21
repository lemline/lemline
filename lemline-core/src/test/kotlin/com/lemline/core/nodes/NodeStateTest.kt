// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.core.json.LemlineJson
import com.lemline.core.set
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NodeStateTest {
    private val jsonFactory = JsonNodeFactory.instance

    @Test
    fun `test constants must not change to maintain expected behavior`() {
        assertEquals(-1, NodeState.CHILD_INDEX_DEFAULT)
        assertEquals(0, NodeState.ATTEMPT_INDEX_DEFAULT)
        assertEquals(-1, NodeState.FOR_INDEX_DEFAULT)
    }

    @Test
    fun `test constants maintain their values for messages backward compatibility`() {
        assertEquals("i", NodeState.CHILD_INDEX)
        assertEquals("try", NodeState.ATTEMPT_INDEX)
        assertEquals("var", NodeState.VARIABLES)
        assertEquals("inp", NodeState.RAW_INPUT)
        assertEquals("out", NodeState.RAW_OUTPUT)
        assertEquals("ctx", NodeState.CONTEXT)
        assertEquals("wid", NodeState.WORKFLOW_ID)
        assertEquals("sat", NodeState.STARTED_AT)
        assertEquals("fori", NodeState.FOR_INDEX)
    }

    @Test
    fun `test default values`() {
        val state = NodeState()
        assertTrue(state.variables.isEmpty())
        assertEquals(NodeState.ATTEMPT_INDEX_DEFAULT, state.attemptIndex)
        assertEquals(NodeState.CHILD_INDEX_DEFAULT, state.childIndex)
        assertNull(state.rawInput)
        assertNull(state.rawOutput)
        assertTrue(state.context.isEmpty())
        assertNull(state.workflowId)
        assertNull(state.startedAt)
        assertEquals(NodeState.FOR_INDEX_DEFAULT, state.forIndex)
    }

    @Nested
    inner class SerializationTests {
        private lateinit var state: NodeState
        private val testInstant = Instant.parse("2024-01-01T00:00:00Z")

        @BeforeEach
        fun setup() {
            state = NodeState().apply {
                variables = LemlineJson.jsonObject.set("testVar", "value")
                attemptIndex = 1
                childIndex = 2
                rawInput = LemlineJson.jsonObject.set("input", "test")
                rawOutput = LemlineJson.jsonObject.set("output", "result")
                context = LemlineJson.jsonObject.set("contextKey", "contextValue")
                workflowId = "test-workflow"
                startedAt = testInstant
                forIndex = 3
            }
        }

        @Nested
        inner class ErrorHandlingTests {
            private val invalidJson = jsonFactory.objectNode()

            @BeforeEach
            fun setup() {
                invalidJson.put(NodeState.VARIABLES, "not-an-object")
                invalidJson.put(NodeState.ATTEMPT_INDEX, "not-an-int")
                invalidJson.put(NodeState.CHILD_INDEX, "not-an-int")
                invalidJson.put(NodeState.CONTEXT, "not-an-object")
                invalidJson.put(NodeState.STARTED_AT, "not-a-date")
                invalidJson.put(NodeState.FOR_INDEX, "not-an-int")
            }

            @Test
            fun `test invalid variables type`() {
                val json = LemlineJson.jsonObject.set(NodeState.VARIABLES, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }

            @Test
            fun `test invalid attempt index type`() {
                val json = LemlineJson.jsonObject.set(NodeState.ATTEMPT_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }

            @Test
            fun `test invalid child index type`() {
                val json = LemlineJson.jsonObject.set(NodeState.CHILD_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }

            @Test
            fun `test invalid context type`() {
                val json = LemlineJson.jsonObject.set(NodeState.CONTEXT, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }

            @Test
            fun `test invalid started at format`() {
                val json = LemlineJson.jsonObject.set(NodeState.STARTED_AT, "invalid-date")
                assertThrows(Exception::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }

            @Test
            fun `test invalid for index type`() {
                val json = LemlineJson.jsonObject.set(NodeState.FOR_INDEX, "invalid")
                assertThrows(IllegalArgumentException::class.java) {
                    LemlineJson.decodeFromElement<NodeState>(json)
                }
            }
        }

        @Test
        fun `default values should not appear during serialization`() {
            val state = NodeState().apply {
                workflowId = "test-workflow"
                childIndex = 1
            }

            assertEquals(
                """{"i":1,"wid":"test-workflow"}""",
                LemlineJson.encodeToString(state)
            )
        }
    }
}
