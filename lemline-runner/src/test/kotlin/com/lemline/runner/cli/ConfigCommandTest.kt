// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.picocli.runtime.annotations.TopCommand
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.IFactory

/**
 * Integration tests for ConfigCommand.
 * Uses @QuarkusTest to leverage the application context and access the Config object.
 */
@QuarkusTest
@TestProfile(InMemoryProfile::class)
class ConfigCommandTest {

    @Inject
    @TopCommand // Inject the main entry point command
    lateinit var mainCommand: MainCommand

    @Inject
    lateinit var factory: IFactory // Inject the CDI-aware factory for command instantiation

    // --- Helper to execute command and capture output ---
    private fun executeAndCapture(vararg args: String): String {
        val cmd = CommandLine(mainCommand, factory)
        val baos = ByteArrayOutputStream()
        val originalOut = System.out
        var exitCode = -1

        try {
            System.setOut(PrintStream(baos, true, StandardCharsets.UTF_8))
            exitCode = cmd.execute(*args)
        } finally {
            System.setOut(originalOut)
        }

        assertEquals(0, exitCode, "Command [${args.joinToString(" ")}] should execute successfully")
        return baos.toString(StandardCharsets.UTF_8).trim()
    }

    /**
     * Tests basic execution of the 'config' command to ensure it runs without errors.
     */
    @Test
    fun `test config command executes successfully`() {
        executeAndCapture("config") // Use helper
    }

    /**
     * Tests that the output of the 'config' command (default format) contains Lemline properties.
     */
    @Test
    fun `test config command output default format contains Lemline properties`() {
        val output = executeAndCapture("config")

        // Verify essential Quarkus properties are present in the output (TEXT format assumed)
        assertTrue(
            output.contains("lemline.database.type="),
            "Output should contain lemline.database.type key-value"
        )
        assertTrue(
            output.contains("lemline.messaging.type="),
            "Output should contain lemline.messaging.type key-value"
        )
        assertFalse(
            output.contains("quarkus.log.level="),
            "Output should not contain quarkus.log.level key-value"
        )
        // Basic check for text format (no JSON/YAML delimiters)
        assertFalse(output.startsWith("{") || output.startsWith("-"), "Default output should resemble text format")
    }

    /**
     * Tests that the output of the 'config' command (default format) contains known properties.
     */
    @Test
    fun `test config command output default format contains Quarkus properties with -a option`() {
        val output1 = executeAndCapture("config", "-a")
        val output2 = executeAndCapture("config", "--all")

        listOf(output1, output2).forEach { output ->
            // Verify essential Quarkus properties are present in the output (TEXT format assumed)
            assertTrue(
                output.contains("lemline.database.type="),
                "Output should contain lemline.database.type key-value"
            )
            assertTrue(
                output.contains("lemline.messaging.type="),
                "Output should contain lemline.messaging.type key-value"
            )
            assertTrue(
                output.contains("quarkus.log.level="),
                "Output should contain quarkus.log.level key-value"
            )
            // Basic check for text format (no JSON/YAML delimiters)
            assertFalse(output.startsWith("{") || output.startsWith("-"), "Default output should resemble text format")
        }
    }

    /**
     * Tests that the global log level option is accepted by the MainCommand.
     */
    @Test
    fun `test main command accepts log level option`() {
        // Test with various valid log levels using different option syntaxes
        executeAndCapture("--log=DEBUG", "config")
        executeAndCapture("-l", "INFO", "config")
        executeAndCapture("--log=WARN", "config")
        executeAndCapture("-l", "ERROR", "config")
        executeAndCapture("--log=FATAL", "config")
        executeAndCapture("--log=TRACE", "config")

        // The LogOptionConverter in MainCommand will cause an exit if the level is invalid,
        // which would be caught by executeAndCapture's exit code check.
        // So, successfully passing executeAndCapture means the log level was accepted.
    }

    // --- Format Option Tests (Assumes --format option exists in ConfigCommand) ---

    /**
     * Tests 'config --format yaml' output.
     * Assumes YAML output uses standard key: value pairs.
     */
    @Test
    fun `test config command output format yaml`() {
        val output1 = executeAndCapture("config", "--format=yaml")
        val output2 = executeAndCapture("config", "-fyaml")

        listOf(output1, output2).forEach { output ->
            // Basic checks for YAML format
            assertTrue(output.contains("lemline:\n  database:\n    "), "output should be in YAML format")
            // Add more specific YAML structure checks if needed (e.g., indentation, list markers)
            assertFalse(output.startsWith("{"), "YAML output should not start with JSON object marker")
        }
    }

    /**
     * Tests 'config --format text' explicitly.
     * Should be similar to the default format test.
     */
    @Test
    fun `test config command output format text`() {
        val output1 = executeAndCapture("config", "--format=properties")
        val output2 = executeAndCapture("config", "-fproperties")

        listOf(output1, output2).forEach { output ->
            assertTrue(output.contains("lemline.database.type="), "output should have properties format")
            assertFalse(output.startsWith("{") || output.startsWith("-"), "Text output should resemble text format")
        }
    }
}
