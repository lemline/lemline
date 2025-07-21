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

class ScriptRunPythonTest {

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `should execute simple Python script successfully`() = runTest {
        // Given
        val script = """
            print('Hello, World!')
            import sys; sys.exit(0)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python"
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
            import sys
            name = 'World'
            for i, arg in enumerate(sys.argv):
                if arg == '--name' and i + 1 < len(sys.argv):
                    name = sys.argv[i + 1]
                    break
            print(f'Hello, {name}!')
            sys.exit(0)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python",
            arguments = mapOf("--name" to "TestUser")
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
            import os, sys
            print(os.environ.get('GREETING', 'Hello'))
            print(os.environ.get('NAME', 'World'), file=sys.stderr)
            sys.exit(0)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python",
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
            import sys
            print('This is normal output')
            print('This is an error message', file=sys.stderr)
            sys.exit(0)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python"
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
            import sys
            print('Something went wrong', file=sys.stderr)
            sys.exit(1)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python"
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
            print('This will not run'
            import sys; sys.exit(0)
        """.trimIndent()

        val scriptRun = Script(
            script = script,
            language = "python"
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
            import os
            files = os.listdir('.')
            print('found' if 'testfile.txt' in files else 'not found')
            import sys; sys.exit(0)
        """.trimIndent()

        // Create a test file in the temp directory
        val testFile = tempDir.resolve("testfile.txt")
        testFile.toFile().writeText("test")

        val scriptRun = Script(
            script = script,
            language = "python",
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
        val script = "console.log('This is JS, but we'll say it's elixir')"
        val scriptRun = Script(
            script = script,
            language = "elixir"
        )

        // When / Then
        val exception = shouldThrow<IllegalArgumentException> {
            scriptRun.execute()
        }
        exception.message shouldBe "Unsupported script language: elixir"
    }
}
