// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.definitions

import com.lemline.runner.cli.GlobalMixin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

@ExperimentalSerializationApi
@ExperimentalTime
class DefinitionCommandTest {

    private lateinit var command: DefinitionCommand
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @BeforeEach
    fun setup() {
        // Create command and inject mocks
        command = DefinitionCommand().apply {
            mixin = GlobalMixin()
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
    }

    @Test
    fun `should show help when no subcommand is provided`() {
        // When
        val exitCode = cmd.execute()

        // Then
        exitCode shouldNotBe 0
        errStream.toString() shouldContain "Missing required subcommand"
        errStream.toString() shouldContain "get"
        errStream.toString() shouldContain "post"
        errStream.toString() shouldContain "delete"
    }

    @Test
    fun `should show help with -h flag`() {
        // When
        val exitCode = cmd.execute("-h")

        // Then
        exitCode shouldBe 0 // Error code for unknown command
        outStream.toString() shouldContain "Manage workflow definitions"
        outStream.toString() shouldContain "get"
        outStream.toString() shouldContain "post"
        outStream.toString() shouldContain "delete"
    }

    @Test
    fun `should show help with --help flag`() {
        // When
        val exitCode = cmd.execute("--help")

        // Then
        exitCode shouldBe 0
        outStream.toString() shouldContain "Manage workflow definitions"
        outStream.toString() shouldContain "get"
        outStream.toString() shouldContain "post"
        outStream.toString() shouldContain "delete"
    }

    @Test
    fun `should show error for unknown subcommand`() {
        // When
        val exitCode = cmd.execute("unknown")

        // Then
        exitCode shouldBe 2 // Error code for unknown command
        errStream.toString() shouldContain "Unmatched argument"
    }
}
