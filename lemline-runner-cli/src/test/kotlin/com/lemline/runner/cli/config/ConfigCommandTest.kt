// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.cli.GlobalMixin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

/**
 * Unit tests for ConfigCommand.
 * Uses manual dependency injection with mocks instead of @QuarkusTest.
 */
@ExperimentalSerializationApi
@ExperimentalTime
class ConfigCommandTest {

    private lateinit var command: ConfigShowCommand
    private lateinit var mockedConfig: Config
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    // Sample configuration properties for testing
    private val testProperties = mapOf(
        "lemline.database.type" to "postgresql",
        "lemline.database.host" to "localhost",
        "lemline.database.port" to "5432",
        "lemline.messaging.type" to "kafka",
        "quarkus.log.level" to "INFO",
        "quarkus.http.port" to "8080"
    )

    @BeforeEach
    fun setup() {
        // Create mock Config
        mockedConfig = mockk()

        // Set up property names iterator
        every { mockedConfig.propertyNames } returns testProperties.keys

        // Set up config values
        testProperties.forEach { (key, value) ->
            val configValue = mockk<ConfigValue>()
            every { configValue.value } returns value
            every { mockedConfig.getConfigValue(key) } returns configValue
        }

        // Create command and inject mocks
        command = ConfigShowCommand().apply {
            this.mixin = GlobalMixin()
            this.lemlineConfig = mockedConfig
        }

        // Save original streams
        originalOut = System.out
        originalErr = System.err

        // Set up capture streams
        outStream = ByteArrayOutputStream()
        errStream = ByteArrayOutputStream()
        System.setOut(PrintStream(outStream))
        System.setErr(PrintStream(errStream))

        cmd = CommandLine(command)
    }

    @AfterEach
    fun cleanup() {
        // Restore original streams
        System.setOut(originalOut)
        System.setErr(originalErr)

        clearAllMocks()
    }

    /**
     * Tests basic execution of the 'config' command to ensure it runs without errors.
     */
    @Test
    fun `test config command executes successfully`() {
        val exitCode = cmd.execute()
        exitCode shouldBe 0
    }

    /**
     * Tests 'config --format yaml' output.
     */
    @Test
    fun `test config command with yaml format`() {
        val exitCode1 = cmd.execute("--format=yaml")
        exitCode1 shouldBe 0
        val output1 = outStream.toString()

        // Reset for second test
        outStream.reset()

        val exitCode2 = cmd.execute("-fyaml")
        exitCode2 shouldBe 0
        val output2 = outStream.toString()

        listOf(output1, output2).forEach { output ->
            // Basic checks for YAML format
            output shouldContain "lemline:"
            output shouldContain "database:"
            // Should not contain Quarkus properties by default
            output shouldNotContain "quarkus:"
        }
    }

    /**
     * Tests 'config --format properties' output.
     */
    @Test
    fun `test config command with properties format`() {
        val exitCode1 = cmd.execute("--format=properties")
        exitCode1 shouldBe 0
        val output1 = outStream.toString()

        // Reset for second test
        outStream.reset()

        val exitCode2 = cmd.execute("-fproperties")
        exitCode2 shouldBe 0
        val output2 = outStream.toString()

        listOf(output1, output2).forEach { output ->
            output shouldContain "lemline.database.type="
            output shouldNotContain "quarkus.log.level="
        }
    }

    @Test
    fun `test config command outputs format yaml by default`() {
        val exitCode = cmd.execute()
        exitCode shouldBe 0
        val output = outStream.toString()

        // Verify Lemline properties are present in the output
        output shouldContain "lemline:"
        output shouldContain "database:"
        // Verify Quarkus properties are not present in the output
        output shouldNotContain "quarkus:"
    }

    @Test
    fun `test config command output default format contains Quarkus properties with -a option`() {
        val exitCode1 = cmd.execute("-a")
        exitCode1 shouldBe 0
        val output1 = outStream.toString()

        // Reset for second test
        outStream.reset()

        val exitCode2 = cmd.execute("--all")
        exitCode2 shouldBe 0
        val output2 = outStream.toString()

        listOf(output1, output2).forEach { output ->
            // Verify Lemline properties are present in the output
            output shouldContain "lemline:"
            // Verify Quarkus properties are present in the output
            output shouldContain "quarkus:"
        }
    }

    @Test
    fun `test config command with all options`() {
        val exitCode1 = cmd.execute("-a", "-fproperties")
        exitCode1 shouldBe 0
        val output1 = outStream.toString()

        // Reset for second test
        outStream.reset()

        val exitCode2 = cmd.execute("--all", "--format=properties")
        exitCode2 shouldBe 0
        val output2 = outStream.toString()

        listOf(output1, output2).forEach { output ->
            // Verify Lemline properties are present in the output
            output shouldContain "lemline.database.type="
            // Verify Quarkus properties are present in the output
            output shouldContain "quarkus.log.level="
        }
    }

    @Test
    fun `test config command shows help`() {
        val exitCode = cmd.execute("-h")
        exitCode shouldBe 0
        val output = outStream.toString()

        output shouldContain "Display current configuration"
        output shouldContain "--format"
        output shouldContain "--all"
    }
}
