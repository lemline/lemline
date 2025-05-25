// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ShellRunParseCommandTest {
    private val shellRun = ShellRun("")

    @Test
    fun `should parse simple command`() {
        val result = shellRun.parseCommand("echo")
        assertEquals(listOf("echo"), result)
    }

    @Test
    fun `should parse command with single argument`() {
        val result = shellRun.parseCommand("echo hello")
        assertEquals(listOf("echo", "hello"), result)
    }

    @Test
    fun `should handle quoted argument with spaces`() {
        val result = shellRun.parseCommand("echo \"hello world\"")
        assertEquals(listOf("echo", "hello world"), result)
    }

    @Test
    fun `should handle quoted argument with spaces with trail`() {
        val result = shellRun.parseCommand("echo \"hello world\"!")
        assertEquals(listOf("echo", "hello world!"), result)
    }

    @Test
    fun `should handle multiple quoted arguments`() {
        val result = shellRun.parseCommand("echo \"hello\" \"world\"")
        assertEquals(listOf("echo", "hello", "world"), result)
    }

    @Test
    fun `should handle mixed quoted and unquoted arguments`() {
        val result = shellRun.parseCommand("echo hello \"beautiful world\"")
        assertEquals(listOf("echo", "hello", "beautiful world"), result)
    }

    @Test
    fun `should handle mixed quoted and unquoted arguments with trail`() {
        val result = shellRun.parseCommand("echo hello \"beautiful world\"!")
        assertEquals(listOf("echo", "hello", "beautiful world!"), result)
    }

    @Test
    fun `should handle empty string`() {
        val result = shellRun.parseCommand("")
        assertEquals(emptyList<String>(), result)
    }
}
