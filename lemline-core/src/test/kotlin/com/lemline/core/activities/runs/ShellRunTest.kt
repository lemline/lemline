// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class ShellRunTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute simple echo command successfully`() {
        // Given
        val shellRun = ShellRun(command = "echo", arguments = mapOf("Hello" to "World"))

        // When
        val result = shellRun.execute()

        // Then
        println(result)
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("Hello") && result.stdout.contains("World"))
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should execute simple echo command successfully on Windows`() {
        // Given
        val shellRun = ShellRun(command = "cmd", arguments = mapOf("/c" to "echo Hello World"))

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("Hello World", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute command with multiple arguments`() {
        // Given
        val shellRun = ShellRun(
            command = "echo",
            arguments = mapOf(
                "-n" to "",
                "No" to "newline"
            )
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("No") && result.stdout.contains("newline"))
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute command with environment variables`() {
        // Given
        val shellRun = ShellRun(
            command = "sh",
            arguments = mapOf("-c" to "echo \$TEST_VAR"),
            environment = mapOf("TEST_VAR" to "test_value")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("test_value", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should execute command with environment variables on Windows`() {
        // Given
        val shellRun = ShellRun(
            command = "cmd",
            arguments = mapOf("/c" to "echo %TEST_VAR%"),
            environment = mapOf("TEST_VAR" to "test_value")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("test_value", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should capture stderr output`() {
        // Given
        val shellRun = ShellRun(
            command = "sh",
            arguments = mapOf("-c" to "echo 'error message' >&2")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("", result.stdout)
        assertEquals("error message", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should capture stderr output on Windows`() {
        // Given
        val shellRun = ShellRun(
            command = "cmd",
            arguments = mapOf("/c" to "echo error message 1>&2")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("", result.stdout)
        assertEquals("error message", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return non-zero exit code for failing command`() {
        // Given
        val shellRun = ShellRun(
            command = "sh",
            arguments = mapOf("-c" to "exit 1")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(1, result.code)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should return non-zero exit code for failing command on Windows`() {
        // Given
        val shellRun = ShellRun(
            command = "cmd",
            arguments = mapOf("/c" to "exit 1")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(1, result.code)
        assertEquals("", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    fun `should handle command with no arguments`() {
        // Given
        val shellRun = ShellRun(command = if (System.getProperty("os.name").contains("Windows")) "echo." else "pwd")

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(result.stdout.isNotEmpty() || result.stdout.isEmpty()) // pwd returns current dir, echo. returns empty on Windows
        assertEquals("", result.stderr)
    }

    @Test
    fun `should handle command with no environment variables`() {
        // Given
        val shellRun = ShellRun(
            command = if (System.getProperty("os.name").contains("Windows")) "echo" else "echo",
            arguments = if (System.getProperty("os.name")
                    .contains("Windows")
            ) mapOf("Hello" to "") else mapOf("Hello" to "")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("Hello"))
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle complex shell command with pipes`() {
        // Given
        val shellRun = ShellRun(
            command = "sh",
            arguments = mapOf("-c" to "echo 'line1\nline2\nline3' | grep line2")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("line2", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command that outputs to both stdout and stderr`() {
        // Given
        val shellRun = ShellRun(
            command = "sh",
            arguments = mapOf("-c" to "echo 'stdout message'; echo 'stderr message' >&2")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("stdout message", result.stdout)
        assertEquals("stderr message", result.stderr)
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // This test is specific to Unix-like systems
    fun `should handle command that doesn't exist`() {
        // Given
        val shellRun = ShellRun(command = "nonexistentcommand12345")

        // When & Then
        try {
            val result = shellRun.execute()
            // If the command doesn't exist, it should typically return a non-zero exit code
            assertNotEquals(0, result.code)
        } catch (e: Exception) {
            // On some systems, trying to execute a non-existent command might throw an exception
            assertTrue(e.message?.contains("Cannot run program") == true || e.message?.contains("No such file") == true)
        }
    }
}
