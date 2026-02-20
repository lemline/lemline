// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.cli.GlobalMixin
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine

class ConfigCreateCommandTest {

    private lateinit var command: ConfigCreateCommand
    private lateinit var cmd: CommandLine
    private lateinit var outStream: ByteArrayOutputStream
    private lateinit var errStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream

    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @BeforeEach
    fun setup() {
        command = ConfigCreateCommand().apply {
            this.mixin = GlobalMixin()
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
    fun `test kafka postgres combination with dry-run`() {
        val exitCode = cmd.execute("--messaging=kafka", "--database=postgresql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: Kafka + PostgreSQL"
        output shouldContain "postgresql:"
        output shouldContain "kafka:"
        output shouldContain "docker compose --profile kafka-pg up -d"
    }

    @Test
    fun `test kafka mysql combination with dry-run`() {
        val exitCode = cmd.execute("-m", "kafka", "-d", "mysql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: Kafka + MySQL"
        output shouldContain "mysql:"
        output shouldContain "kafka:"
        output shouldContain "docker compose --profile kafka-mysql up -d"
    }

    @Test
    fun `test rabbitmq postgres combination with dry-run`() {
        val exitCode = cmd.execute("--messaging=rabbitmq", "--database=postgresql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: RabbitMQ + PostgreSQL"
        output shouldContain "postgresql:"
        output shouldContain "rabbitmq:"
        output shouldContain "docker compose --profile rabbit-pg up -d"
    }

    @Test
    fun `test rabbitmq mysql combination with dry-run`() {
        val exitCode = cmd.execute("--messaging=rabbitmq", "--database=mysql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: RabbitMQ + MySQL"
        output shouldContain "mysql:"
        output shouldContain "rabbitmq:"
    }

    @Test
    fun `test pgmq postgres combination with dry-run`() {
        val exitCode = cmd.execute("--messaging=pgmq", "--database=postgresql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: PGMQ + PostgreSQL"
        output shouldContain "postgresql:"
        output shouldContain "pgmq:"
        output shouldContain "docker compose --profile pgmq up -d"
    }

    @Test
    fun `test pgmq mysql combination with dry-run`() {
        val exitCode = cmd.execute("--messaging=pgmq", "--database=mysql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: PGMQ + MySQL"
        output shouldContain "mysql:"
        output shouldContain "pgmq:"
    }

    @Test
    fun `test invalid messaging type shows error`() {
        val exitCode = cmd.execute("--messaging=invalid", "--database=postgresql", "--dry-run")
        exitCode shouldBe 2 // Picocli returns 2 for parameter errors

        val output = errStream.toString()
        output shouldContain "Invalid messaging type"
    }

    @Test
    fun `test invalid database type shows error`() {
        val exitCode = cmd.execute("--messaging=kafka", "--database=invalid", "--dry-run")
        exitCode shouldBe 2 // Picocli returns 2 for parameter errors

        val output = errStream.toString()
        output shouldContain "Invalid database type"
    }

    @Test
    fun `test file is written to default path`() {
        val outputPath = tempDir.resolve("lemline.yaml")
        val exitCode = cmd.execute(
            "--messaging=kafka",
            "--database=postgresql",
            "--output=${outputPath.toAbsolutePath()}"
        )
        exitCode shouldBe 0

        outputPath.exists() shouldBe true
        val content = outputPath.readText()
        content shouldContain "# Lemline Configuration: Kafka + PostgreSQL"
        content shouldContain "postgresql:"
        content shouldContain "kafka:"

        val output = outStream.toString()
        output shouldContain "Configuration written to:"
        output shouldContain "Next steps:"
    }

    @Test
    fun `test force overwrites existing file`() {
        val outputPath = tempDir.resolve("existing.yaml")
        Files.writeString(outputPath, "existing content")

        val exitCode = cmd.execute(
            "--messaging=kafka",
            "--database=postgresql",
            "--output=${outputPath.toAbsolutePath()}",
            "--force"
        )
        exitCode shouldBe 0

        outputPath.exists() shouldBe true
        val content = outputPath.readText()
        content shouldContain "# Lemline Configuration: Kafka + PostgreSQL"
        content shouldNotContain "existing content"
    }

    @Test
    fun `test error when file exists without force`() {
        val outputPath = tempDir.resolve("existing.yaml")
        Files.writeString(outputPath, "existing content")

        val exitCode = cmd.execute(
            "--messaging=kafka",
            "--database=postgresql",
            "--output=${outputPath.toAbsolutePath()}"
        )
        exitCode shouldBe 2 // Picocli returns 2 for ParameterException

        val error = errStream.toString()
        error shouldContain "already exists"
        error shouldContain "--force"

        // Original content should be preserved
        outputPath.readText() shouldBe "existing content"
    }

    @Test
    fun `test custom output path`() {
        val customPath = tempDir.resolve("custom-config.yaml")
        val exitCode = cmd.execute(
            "--messaging=rabbitmq",
            "--database=mysql",
            "--output=${customPath.toAbsolutePath()}"
        )
        exitCode shouldBe 0

        customPath.exists() shouldBe true
        val content = customPath.readText()
        content shouldContain "# Lemline Configuration: RabbitMQ + MySQL"
        content shouldContain "mysql:"
        content shouldContain "rabbitmq:"
    }

    @Test
    fun `test dry-run does not write file`() {
        val outputPath = tempDir.resolve("test.yaml")
        val exitCode = cmd.execute(
            "--messaging=kafka",
            "--database=postgresql",
            "--output=${outputPath.toAbsolutePath()}",
            "--dry-run"
        )
        exitCode shouldBe 0

        outputPath.exists() shouldBe false

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: Kafka + PostgreSQL"
    }

    @Test
    fun `test short flags work correctly`() {
        val exitCode = cmd.execute("-m", "kafka", "-d", "postgresql", "--dry-run")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "# Lemline Configuration: Kafka + PostgreSQL"
    }

    @Test
    fun `test help shows command description`() {
        val exitCode = cmd.execute("--help")
        exitCode shouldBe 0

        val output = outStream.toString()
        output shouldContain "Generate a new configuration file"
        output shouldContain "--messaging"
        output shouldContain "--database"
        output shouldContain "--output"
        output shouldContain "--force"
        output shouldContain "--dry-run"
    }
}
