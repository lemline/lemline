// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.NodePosition
import com.lemline.common.values.NodePosition.Companion.root
import com.lemline.common.values.Token
import com.lemline.core.random.random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class NodePositionTest {

    @Test
    fun `root pointer and toString should be empty`() {
        val root = root
        assertEquals("/", root.toString())
        // Also via parse
        assertEquals(NodePosition.root, NodePosition("/"))
    }

    @Test
    fun `building path with addName and addToken`() {
        val pos = root
            .addToken(Token.DO)
            .addToken(Token.RUN)
            .addName("custom")

        assertEquals("/do/run/custom", pos.toString())
    }

    @Test
    fun `building path with addIndex for RFC 6901 compliance`() {
        val pos = root
            .addToken(Token.DO)
            .addIndex(0)
            .addName("taskA")

        assertEquals("/do/0/taskA", pos.toString())
    }

    @Test
    fun `nested path with indices`() {
        val pos = root
            .addToken(Token.DO)
            .addIndex(0)
            .addName("outer")
            .addToken(Token.TRY)
            .addToken(Token.DO)
            .addIndex(1)
            .addName("inner")

        assertEquals("/do/0/outer/try/do/1/inner", pos.toString())
    }

    @Test
    fun `fork branches with indices`() {
        val pos = root
            .addToken(Token.DO)
            .addIndex(0)
            .addName("forkTask")
            .addToken(Token.FORK)
            .addToken(Token.BRANCHES)
            .addIndex(2)
            .addName("branchC")

        assertEquals("/do/0/forkTask/fork/branches/2/branchC", pos.toString())
    }

    @Nested
    inner class AddIndexValidation {
        @Test
        fun `index must be non-negative`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                root.addIndex(-1)
            }
            assertTrue(ex.message!!.contains("non-negative"))
        }

        @Test
        fun `zero index is valid`() {
            val pos = root.addIndex(0)
            assertEquals("/0", pos.toString())
        }
    }

    @Nested
    inner class AddNameValidation {
        @Test
        fun `name must not contain slash`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                root.addName("a/b")
            }
            assertTrue(ex.message!!.contains("must not contain '/"))
        }

        @Test
        fun `name must not be an integer`() {
            val ex = assertThrows(IllegalArgumentException::class.java) {
                root.addName("123")
            }
            assertTrue(ex.message!!.contains("must not be an integer"))
        }

        @Test
        fun `name must not be a reserved token`() {
            // Use one known token, e.g. "do"
            val ex = assertThrows(IllegalArgumentException::class.java) {
                root.addName(Token.DO.token)
            }
            assertTrue(ex.message!!.contains("must not be one of"))
        }

        @Test
        fun `valid name is appended`() {
            val pos = root.addName("taskA")
            assertEquals("/taskA", pos.toString())
        }
    }

    @Test
    fun `nodeName returns last segment`() {
        assertEquals("taskA", NodePosition("/do/taskA").nodeName)
        assertEquals("do", NodePosition("/do").nodeName)
        assertEquals("", root.nodeName)
        assertEquals("custom", NodePosition("/do/run/custom").nodeName)
    }

    @Test
    fun `parse handles various formats`() {
        assertEquals(root, NodePosition("/"))
        assertEquals("/do", NodePosition("/do").toString())
        assertEquals("/do/taskA", NodePosition("/do/taskA").toString())
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
            val root = root
            val json = LemlineJson.encodeToString(root)
            // Empty pointer serializes to empty JSON string
            assertEquals("\"/\"", json)

            val decoded = LemlineJson.decodeFromString<NodePosition>(json)
            assertEquals(root, decoded)
            assertEquals("/", decoded.toString())
        }


    }
}
