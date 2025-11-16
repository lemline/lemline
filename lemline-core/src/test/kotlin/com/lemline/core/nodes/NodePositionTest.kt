// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
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
        // Also via PositionPointer root
        assertEquals(NodePosition.root, PositionPointer.root.toPosition())
        assertNull(root.parent)
    }

    @Test
    fun `building path with addName addIndex and addToken`() {
        val pos = NodePosition.root
            .addToken(Token.DO)
            .addIndex(0)
            .addToken(Token.RUN)
            .addName("custom")

        assertEquals("/do/0/run/custom", pos.toString())
        assertEquals("/do/0/run/custom", pos.positionPointer.toString())

        // Parent chain
        assertEquals("/do/0/run", pos.parent!!.toString())
        assertEquals("/do/0", pos.parent!!.parent!!.toString())
        assertEquals("/do", pos.parent!!.parent!!.parent!!.toString())
        assertEquals("/", pos.parent!!.parent!!.parent!!.parent!!.toString())
        assertNull(pos.parent!!.parent!!.parent!!.parent!!.parent)
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
    fun `addIndex appends numeric index as path segment`() {
        val pos = NodePosition.root.addIndex(42)
        assertEquals("/42", pos.toString())
        val neg = NodePosition.root.addIndex(-1)
        assertEquals("/-1", neg.toString())
        val zero = NodePosition.root.addIndex(0)
        assertEquals("/0", zero.toString())
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
