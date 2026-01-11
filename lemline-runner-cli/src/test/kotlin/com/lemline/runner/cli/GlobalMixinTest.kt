// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine
import picocli.CommandLine.Command

/**
 * Unit tests for GlobalMixin.
 * Tests parsing of global CLI options like --json and log level flags.
 */
class GlobalMixinTest {

    private lateinit var mixin: GlobalMixin
    private lateinit var cmd: CommandLine

    /**
     * Test command that uses the GlobalMixin to verify option parsing.
     */
    @Command(name = "test")
    class TestCommand {
        @CommandLine.Mixin
        lateinit var mixin: GlobalMixin

        @CommandLine.Option(names = ["--dummy"])
        var dummy: Boolean = false
    }

    @BeforeEach
    fun setup() {
        mixin = GlobalMixin()
        cmd = CommandLine(TestCommand())
    }

    @Test
    fun `json flag defaults to false`() {
        cmd.parseArgs()
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.jsonMode shouldBe false
    }

    @Test
    fun `json flag is parsed correctly`() {
        cmd.parseArgs("--json")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.jsonMode shouldBe true
    }

    @Test
    fun `json flag can be combined with log level flags`() {
        cmd.parseArgs("--json", "--debug")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.jsonMode shouldBe true
        testCmd.mixin.logLevelGroup?.debugMode shouldBe true
    }

    @Test
    fun `json flag can be combined with config option`() {
        cmd.parseArgs("--json", "--config=/path/to/config.yaml")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.jsonMode shouldBe true
        testCmd.mixin.configFile shouldBe "/path/to/config.yaml"
    }

    @Test
    fun `debug flag is parsed correctly`() {
        cmd.parseArgs("--debug")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.logLevelGroup?.debugMode shouldBe true
    }

    @Test
    fun `info flag is parsed correctly`() {
        cmd.parseArgs("--info")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.logLevelGroup?.infoMode shouldBe true
    }

    @Test
    fun `warn flag is parsed correctly`() {
        cmd.parseArgs("--warn")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.logLevelGroup?.warnMode shouldBe true
    }

    @Test
    fun `error flag is parsed correctly`() {
        cmd.parseArgs("--error")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.logLevelGroup?.errorMode shouldBe true
    }

    @Test
    fun `config option short form is parsed correctly`() {
        cmd.parseArgs("-c", "/path/to/config.yaml")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.configFile shouldBe "/path/to/config.yaml"
    }

    @Test
    fun `config option long form is parsed correctly`() {
        cmd.parseArgs("--config=/path/to/config.yaml")
        val testCmd = cmd.getCommand<TestCommand>()
        testCmd.mixin.configFile shouldBe "/path/to/config.yaml"
    }
}
