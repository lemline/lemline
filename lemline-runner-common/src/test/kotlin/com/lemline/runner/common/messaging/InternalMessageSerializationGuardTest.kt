// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class InternalMessageSerializationGuardTest {

    @Test
    fun `runner runtime should not use direct workflow state json serialization`() {
        val repoRoot = findRepoRoot()
        val targets = listOf(
            repoRoot.resolve("lemline-runner/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-common/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-definitions/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-failures/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-forks/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-listeners/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-messaging-pgmq/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-parents/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-retries/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-schedules/src/main/kotlin"),
            repoRoot.resolve("lemline-runner-waits/src/main/kotlin")
        )

        val forbidden = listOf(
            Regex("""\bWorkflowState\.fromJsonString\s*\("""),
            Regex("""\bworkflowState\??\.toJsonString\s*\(""")
        )

        val violations = mutableListOf<String>()
        targets.filter { it.isDirectory() }.forEach { dir ->
            Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .forEach { file ->
                        val source = file.readText()
                        source.lineSequence()
                            .forEachIndexed { index, line ->
                                if (forbidden.any { it.containsMatchIn(line) }) {
                                    violations += "${file.relativeTo(repoRoot)}:${index + 1}: $line"
                                }
                            }
                    }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Direct WorkflowState JSON serialization found in runner runtime:\n${violations.joinToString("\n")}"
        )
    }

    private fun findRepoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            if (current.resolve("settings.gradle.kts").exists()) {
                return current
            }
            val parent = current.parent ?: error("Unable to find repository root from $current")
            current = parent
        }
    }
}
