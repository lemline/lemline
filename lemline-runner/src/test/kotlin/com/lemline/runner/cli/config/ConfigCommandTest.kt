// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.cli.MainCommand
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.picocli.runtime.annotations.TopCommand
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import picocli.CommandLine

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
    lateinit var factory: CommandLine.IFactory // Inject the CDI-aware factory for command instantiation

    // --- Helper to execute command and capture output ---
    private fun executeAndCapture(vararg args: String): String {
        val cmd = CommandLine(mainCommand, factory)
        val baos = ByteArrayOutputStream()
        val originalOut = System.out
        var exitCode: Int

        try {
            System.setOut(PrintStream(baos, true, StandardCharsets.UTF_8))
            exitCode = cmd.execute(*args)
        } finally {
            System.setOut(originalOut)
        }

        Assertions.assertEquals(0, exitCode, "Command [${args.joinToString(" ")}] should execute successfully")
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
     * Tests 'config --format yaml' output.
     * Assumes YAML output uses standard key: value pairs.
     */
    @Test
    fun `test config command with yaml format`() {
        val output1 = executeAndCapture("config", "--format=yaml")
        val output2 = executeAndCapture("config", "-fyaml")

        listOf(output1, output2).forEach { output ->
            // Basic checks for YAML format
            Assertions.assertTrue(output.contains("lemline:\n  database:\n    "), "output should be in YAML format")
            // should not contain Quarkus properties
            Assertions.assertFalse(output.contains("quarkus:"), "output should not contain Quarkus properties")
        }
    }

    /**
     * Tests 'config --format text' explicitly.
     * Should be similar to the default format test.
     */
    @Test
    fun `test config command with properties format`() {
        val output1 = executeAndCapture("config", "--format=properties")
        val output2 = executeAndCapture("config", "-fproperties")

        listOf(output1, output2).forEach { output ->
            Assertions.assertTrue(output.contains("lemline.database.type="), "output should have properties format")
            Assertions.assertFalse(
                output.contains("quarkus.log.level="),
                "output should not contain Quarkus properties"
            )
        }
    }

    @Test
    fun `test config command outputs format yaml by default`() {
        val output = executeAndCapture("config") // Use helper

        // Verify Lemline properties are present in the output
        Assertions.assertTrue(output.contains("lemline:\n  database:\n    "), "output should be in YAML format")
        // Verify Quarkus properties are not present in the output
        Assertions.assertFalse(output.contains("quarkus:\n"), "output should not contain Quarkus properties")

    }

    @Test
    fun `test config command output default format contains Quarkus properties with -a option`() {
        val output1 = executeAndCapture("config", "-a")
        val output2 = executeAndCapture("config", "--all")

        listOf(output1, output2).forEach { output ->
            // Verify Lemline properties are present in the output
            Assertions.assertTrue(output.contains("lemline:\n  database:\n    "), "output should be in YAML format")
            // Verify Quarkus properties are present in the output
            Assertions.assertTrue(output.contains("quarkus:\n"), "output should contain Quarkus properties")
        }
    }

    @Test
    fun `test config command with all options`() {
        val output1 = executeAndCapture("config", "-a", "-fproperties")
        val output2 = executeAndCapture("config", "--all", "--format=properties")

        listOf(output1, output2).forEach { output ->
            // Verify Lemline properties are present in the output
            Assertions.assertTrue(output.contains("lemline.database.type="), "output should have properties format")
            // Verify Quarkus properties are present in the output
            Assertions.assertTrue(output.contains("quarkus.log.level="), "output should contain Quarkus properties")
        }
    }

    /**
     * Tests that the global log level option is accepted by the MainCommand.
     */
    @Test
    fun `test config command accepts log level option`() {
        // Test with various log levels before and after the command
        executeAndCapture("--warn", "config")
        executeAndCapture("--info", "config")
        executeAndCapture("--error", "config")
        executeAndCapture("--debug", "config")
        executeAndCapture("config", "--warn")
        executeAndCapture("config", "--info")
        executeAndCapture("config", "--error")
        executeAndCapture("config", "--debug")
    }
}
