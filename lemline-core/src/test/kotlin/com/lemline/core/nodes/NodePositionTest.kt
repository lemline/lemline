// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.Token
import com.lemline.core.random.random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NodePositionTest {

    @Test
    fun `root pointer and toString should be empty`() {
        val root = NodePosition.root
        assertEquals("/", root.toString())
        // Also via parse
        assertEquals(NodePosition.root, NodePosition.parse("/"))
        assertNull(root.parent)
    }

    @Test
    fun `building path with addName and addToken`() {
        val pos = NodePosition.root
            .addToken(Token.DO)
            .addToken(Token.RUN)
            .addName("custom")

        assertEquals("/do/run/custom", pos.toString())

        // Parent chain
        assertEquals("/do/run", pos.parent!!.toString())
        assertEquals("/do", pos.parent!!.parent!!.toString())
        assertEquals("/", pos.parent!!.parent!!.parent!!.toString())
        assertNull(pos.parent!!.parent!!.parent!!.parent)
    }

    @Nested
    inner class AddNameValidation {
        @Test
        fun `name must not contain slash`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                NodePosition.root.addName("a/b")
            }
            assertTrue(ex.message!!.contains("must not contain '/"))
        }

        @Test
        fun `name must not be an integer`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                NodePosition.root.addName("123")
            }
            assertTrue(ex.message!!.contains("must not be an integer"))
        }

        @Test
        fun `name must not be a reserved token`() {
            // Use one known token, e.g. "do"
            val ex = assertThrows(IllegalArgumentException::class.java) {
                NodePosition.root.addName(Token.DO.token)
            }
            assertTrue(ex.message!!.contains("must not be one of"))
        }

        @Test
        fun `valid name is appended`() {
            val pos = NodePosition.root.addName("taskA")
            assertEquals("/taskA", pos.toString())
        }
    }

    @Test
    fun `nodeName returns last segment`() {
        assertEquals("taskA", NodePosition.parse("/do/taskA").nodeName)
        assertEquals("do", NodePosition.parse("/do").nodeName)
        assertEquals("", NodePosition.root.nodeName)
        assertEquals("custom", NodePosition.parse("/do/run/custom").nodeName)
    }

    @Test
    fun `segments returns path components`() {
        assertEquals(listOf("do", "taskA"), NodePosition.parse("/do/taskA").segments())
        assertEquals(listOf("do"), NodePosition.parse("/do").segments())
        assertEquals(emptyList<String>(), NodePosition.root.segments())
        assertEquals(listOf("do", "run", "custom"), NodePosition.parse("/do/run/custom").segments())
    }

    @Test
    fun `parse handles various formats`() {
        assertEquals(NodePosition.root, NodePosition.parse("/"))
        assertEquals(NodePosition.root, NodePosition.parse(""))
        assertEquals("/do", NodePosition.parse("/do").toString())
        assertEquals("/do/taskA", NodePosition.parse("/do/taskA").toString())
        assertEquals("/do/taskA", NodePosition.parse(" /do/taskA ").toString()) // with whitespace
    }

    @Nested
    inner class Serialization {
        @Test
        fun `serialize and deserialize random`() {
            val original = NodePosition.random()
            val json = LemlineJson.encodeToString(original)

            println(json)
            val decoded = LemlineJson.decodeFromString<NodePosition>(json)
            assertEquals(original, decoded)
        }

        @Test
        fun `serialize and deserialize root`() {
            val root = NodePosition.root
            val json = LemlineJson.encodeToString(root)
            // Empty pointer serializes to empty JSON string
            assertEquals("\"/\"", json)

            val decoded = NodePosition.fromJsonString(json)
            assertEquals(root, decoded)
            assertEquals("/", decoded.toString())
        }


    }
}

fun main() {
    println(NodePosition.fromJsonString("\"/[B@29ee8374/[B@5a20c592/[B@29abec7e\""))
}
