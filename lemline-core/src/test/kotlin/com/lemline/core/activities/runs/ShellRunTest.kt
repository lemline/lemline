// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

class ShellRunTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute simple echo command successfully`() = runTest {
        // Given
        val shellRun = ShellRun(command = "echo", arguments = mapOf("Hello" to "World"))

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(result.stdout.contains("Hello") && result.stdout.contains("World"))
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should execute simple echo command successfully on Windows`() = runTest {
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
    fun `should execute command with multiple arguments`() = runTest {
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
    fun `should execute command with environment variables`() = runTest {
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
    fun `should execute command with environment variables on Windows`() = runTest {
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
    fun `should handle command with quoted arguments`() = runTest {
        // Given
        val shellRun = ShellRun(
            command = "echo \"Hello World\""
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("Hello World", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with multiple quoted arguments`() = runTest {
        // Given
        val shellRun = ShellRun(
            command = "echo \"Hello\" \"World\" \"with spaces\""
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("Hello World with spaces", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with mixed quoted and unquoted arguments`() = runTest {
        // Given
        val shellRun = ShellRun(
            command = "echo Hello \"beautiful World\"!"
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("Hello beautiful World!", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with nested quotes`() = runTest {
        // Given
        val shellRun = ShellRun(
            command = "echo \"He said, 'Hello World'\""
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("He said, 'Hello World'", result.stdout)
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with additional arguments map`() = runTest {
        // Given
        val shellRun = ShellRun(
            command = "echo \"Hello\"",
            arguments = mapOf("World" to "from test")
        )

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(
            result.stdout.contains("Hello") &&
                result.stdout.contains("World") &&
                result.stdout.contains("from test")
        )
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should capture stderr output`() = runTest {
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
    fun `should capture stderr output on Windows`() = runTest {
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
    fun `should return non-zero exit code for failing command`() = runTest {
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
    fun `should return non-zero exit code for failing command on Windows`() = runTest {
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
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with no arguments on Unix`() = runTest {
        // Given
        val shellRun = ShellRun(command = "pwd")

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertTrue(result.stdout.isNotBlank())
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should handle command with no arguments on Windows`() = runTest {
        // Given
        val shellRun = ShellRun(command = "cmd", arguments = mapOf("/c" to "echo Hello"))

        // When
        val result = shellRun.execute()

        // Then
        assertEquals(0, result.code)
        assertEquals("Hello", result.stdout.trim())
        assertEquals("", result.stderr)
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command that outputs to both stdout and stderr`() = runTest {
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
    fun `should handle command that doesn't exist on Unix`() = runTest {
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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `should handle command that doesn't exist on Windows`() = runTest {
        // Given
        val shellRun = ShellRun(command = "nonexistentcommand12345")

        // When & Then
        try {
            val result = shellRun.execute()
            // On Windows, trying to execute a non-existent command typically returns error code 1
            assertNotEquals(0, result.code)
        } catch (e: Exception) {
            // Some Windows systems might throw an exception instead
            assertTrue(e.message?.contains("Cannot run program") == true)
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with spaces in path`() = runTest {
        // Create a temporary directory with a space in the name
        val tempDir = java.nio.file.Files.createTempDirectory("test dir").toFile()
        try {
            val scriptFile = tempDir.resolve("test script.sh")
            scriptFile.writeText(
                """
                #!/bin/bash
                echo "Script with spaces in path executed with args: $@"
                exit 0
            """.trimIndent()
            )
            scriptFile.setExecutable(true)

            // Test with a command containing spaces and arguments
            val shellRun = ShellRun(
                command = "\"${scriptFile.absolutePath}\"",
                arguments = mapOf("arg1" to "value 1", "arg2" to "value 2")
            )

            // When
            val result = shellRun.execute()

            // Then
            assertEquals(0, result.code)
            assertTrue(
                result.stdout.trim()
                    .contains("Script with spaces in path executed with args: arg1 value 1 arg2 value 2"),
                "Expected output to contain 'Script with spaces in path executed with args: arg1 value 1 arg2 value 2' but was: '${result.stdout}'"
            )
            assertEquals("", result.stderr)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle command with spaces and quoted arguments`() = runTest {
        // Create a temporary directory with a space in the name
        val tempDir = java.nio.file.Files.createTempDirectory("test dir").toFile()
        try {
            val scriptFile = tempDir.resolve("test script.sh")
            scriptFile.writeText(
                """
                #!/bin/bash
                echo "Script with spaces in path executed with args: $1 $2"
                exit 0
            """.trimIndent()
            )
            scriptFile.setExecutable(true)

            // Test with a command containing spaces and quoted arguments
            val shellRun = ShellRun(
                command = "\"${scriptFile.absolutePath}\" \"first argument\" \"second argument\""
            )

            // When
            val result = shellRun.execute()

            // Then
            assertEquals(0, result.code)
            assertTrue(
                result.stdout.trim()
                    .contains("Script with spaces in path executed with args: first argument second argument"),
                "Expected output to contain 'Script with spaces in path executed with args: first argument second argument' but was: '${result.stdout}'"
            )
            assertEquals("", result.stderr)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
