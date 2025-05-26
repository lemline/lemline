// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

class ScriptRunTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute simple JavaScript script successfully`() = runTest {
        // Given
        val script = """
            console.log('Hello, World!');
            process.exit(0);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js"
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 0
        result.stdout.trim() shouldBe "Hello, World!"
        result.stderr shouldBe ""
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle script with arguments`() = runTest {
        // Given
        val script = """
            // Process arguments in the format: --key value
            let name = 'World';
            for (let i = 0; i < process.argv.length - 1; i++) {
                if (process.argv[i] === '--name') {
                    name = process.argv[i + 1];
                    break;
                }
            }
            console.log('Hello, ' + name + '!');
            process.exit(0);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js",
            arguments = mapOf("name" to "TestUser")
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 0
        result.stdout.trim() shouldBe "Hello, TestUser!"
        result.stderr shouldBe ""
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle script with environment variables`() = runTest {
        // Given
        val script = """
            console.log(process.env.GREETING || 'Hello');
            console.error(process.env.NAME || 'World');
            process.exit(0);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js",
            environment = mapOf(
                "GREETING" to "Welcome",
                "NAME" to "User"
            )
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 0
        result.stdout.trim() shouldBe "Welcome"
        result.stderr.trim() shouldBe "User"
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should capture stderr output`() = runTest {
        // Given
        val script = """
            console.error('This is an error message');
            console.log('This is normal output');
            process.exit(0);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js"
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 0
        result.stdout.trim() shouldBe "This is normal output"
        result.stderr.trim() shouldBe "This is an error message"
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should return non-zero exit code on script error`() = runTest {
        // Given
        val script = """
            console.error('Something went wrong');
            process.exit(1);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js"
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 1
        result.stderr.trim() shouldBe "Something went wrong"
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should handle script with syntax error`() = runTest {
        // Given
        val script = """
            console.log('This will not run'
            process.exit(0);
        """.trimIndent()

        val scriptRun = ScriptRun(
            script = script,
            language = "js"
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldNotBe 0
        result.stderr shouldContain "SyntaxError"
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute script in specified working directory`(
        @TempDir tempDir: Path
    ) = runTest {
        // Given
        val script = """
            const fs = require('fs');
            const files = fs.readdirSync('.');
            console.log(files.includes('testfile.txt') ? 'found' : 'not found');
            process.exit(0);
        """.trimIndent()

        // Create a test file in the temp directory
        val testFile = tempDir.resolve("testfile.txt")
        testFile.toFile().writeText("test")

        val scriptRun = ScriptRun(
            script = script,
            language = "js",
            workingDir = tempDir
        )

        // When
        val result = scriptRun.execute()

        // Then
        result.code shouldBe 0
        result.stdout.trim() shouldBe "found"
    }


    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should throw exception for unsupported language`() = runTest {
        // Given
        val script = "print('This is Python, but we'll say it's JavaScript')"
        val scriptRun = ScriptRun(
            script = script,
            language = "python"
        )

        // When / Then
        val exception = shouldThrow<IllegalArgumentException> {
            scriptRun.execute()
        }
        exception.message shouldBe "Unsupported script language: python"
    }
}
