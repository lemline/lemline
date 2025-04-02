package com.lemline.sw.tasks

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

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
        assertTrue(state.variables.isEmpty)
        assertEquals(NodeState.ATTEMPT_INDEX_DEFAULT, state.attemptIndex)
        assertEquals(NodeState.CHILD_INDEX_DEFAULT, state.childIndex)
        assertNull(state.rawInput)
        assertNull(state.rawOutput)
        assertTrue(state.context.isEmpty)
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
                variables = jsonFactory.objectNode().put("testVar", "value")
                attemptIndex = 1
                childIndex = 2
                rawInput = jsonFactory.objectNode().put("input", "test")
                rawOutput = jsonFactory.objectNode().put("output", "result")
                context = jsonFactory.objectNode().put("contextKey", "contextValue")
                workflowId = "test-workflow"
                startedAt = DateTimeDescriptor.from(testInstant)
                forIndex = 3
            }
        }

        @Test
        fun `test serialization to JSON`() {
            val json = state.toJson()
            assertNotNull(json)

            assertEquals("value", json?.get(NodeState.VARIABLES)?.get("testVar")?.asText())
            assertEquals(1, json?.get(NodeState.ATTEMPT_INDEX)?.asInt())
            assertEquals(2, json?.get(NodeState.CHILD_INDEX)?.asInt())
            assertEquals("test", json?.get(NodeState.RAW_INPUT)?.get("input")?.asText())
            assertEquals("result", json?.get(NodeState.RAW_OUTPUT)?.get("output")?.asText())
            assertEquals("contextValue", json?.get(NodeState.CONTEXT)?.get("contextKey")?.asText())
            assertEquals("test-workflow", json?.get(NodeState.WORKFLOW_ID)?.asText())
            assertEquals(testInstant.toString(), json?.get(NodeState.STARTED_AT)?.asText())
            assertEquals(3, json?.get(NodeState.FOR_INDEX)?.asInt())
        }

        @Test
        fun `test deserialization from JSON`() {
            val json = state.toJson()
            val deserializedState = NodeState.fromJson(json!!)

            assertEquals("value", deserializedState.variables.get("testVar").asText())
            assertEquals(1, deserializedState.attemptIndex)
            assertEquals(2, deserializedState.childIndex)
            assertEquals("test", deserializedState.rawInput?.get("input")?.asText())
            assertEquals("result", deserializedState.rawOutput?.get("output")?.asText())
            assertEquals("contextValue", deserializedState.context.get("contextKey").asText())
            assertEquals("test-workflow", deserializedState.workflowId)
            assertEquals(testInstant, deserializedState.startedAt?.iso8601()?.let { Instant.parse(it) })
            assertEquals(3, deserializedState.forIndex)
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
            val json = jsonFactory.objectNode().put(NodeState.VARIABLES, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                NodeState.fromJson(json)
            }
        }

        @Test
        fun `test invalid attempt index type`() {
            val json = jsonFactory.objectNode().put(NodeState.ATTEMPT_INDEX, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                NodeState.fromJson(json)
            }
        }

        @Test
        fun `test invalid child index type`() {
            val json = jsonFactory.objectNode().put(NodeState.CHILD_INDEX, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                NodeState.fromJson(json)
            }
        }

        @Test
        fun `test invalid context type`() {
            val json = jsonFactory.objectNode().put(NodeState.CONTEXT, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                NodeState.fromJson(json)
            }
        }

        @Test
        fun `test invalid started at format`() {
            val json = jsonFactory.objectNode().put(NodeState.STARTED_AT, "invalid-date")
            assertThrows(Exception::class.java) {
                NodeState.fromJson(json)
            }
        }

        @Test
        fun `test invalid for index type`() {
            val json = jsonFactory.objectNode().put(NodeState.FOR_INDEX, "invalid")
            assertThrows(IllegalArgumentException::class.java) {
                NodeState.fromJson(json)
            }
        }
    }

    @Test
    fun `test default state serialization returns null`() {
        val state = NodeState()
        assertNull(state.toJson())
    }

    @Test
    fun `test partial state serialization`() {
        val state = NodeState().apply {
            workflowId = "test-workflow"
            childIndex = 1
        }

        val json = state.toJson()
        assertNotNull(json)
        assertEquals("test-workflow", json?.get(NodeState.WORKFLOW_ID)?.asText())
        assertEquals(1, json?.get(NodeState.CHILD_INDEX)?.asInt())
        assertNull(json?.get(NodeState.VARIABLES))
        assertNull(json?.get(NodeState.RAW_INPUT))
    }
}