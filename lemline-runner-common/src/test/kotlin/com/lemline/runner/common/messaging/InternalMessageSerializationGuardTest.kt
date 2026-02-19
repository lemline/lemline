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

    @Test
    fun `internal protobuf schema should not define legacy config_json fields`() {
        val repoRoot = findRepoRoot()
        val protoRoot = repoRoot.resolve("lemline-messages-proto/src/main/proto/internal")
        val violations = mutableListOf<String>()
        val forbidden = Regex("""\bconfig_json\b""")

        Files.walk(protoRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".proto") }
                .forEach { file ->
                    file.readText()
                        .lineSequence()
                        .forEachIndexed { index, line ->
                            if (forbidden.containsMatchIn(line)) {
                                violations += "${file.relativeTo(repoRoot)}:${index + 1}: $line"
                            }
                        }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Legacy protobuf config_json fields found:\n${violations.joinToString("\n")}"
        )
    }

    @Test
    fun `core protobuf mappers should not use legacy configJson accessors`() {
        val repoRoot = findRepoRoot()
        val mapperRoot = repoRoot.resolve("lemline-core/src/main/kotlin/com/lemline/core/states/protobuf")
        val violations = mutableListOf<String>()
        val forbidden = listOf(
            Regex("""\bsetConfigJson\s*\("""),
            Regex("""\bconfigJson\b""")
        )

        Files.walk(mapperRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .forEach { file ->
                    file.readText()
                        .lineSequence()
                        .forEachIndexed { index, line ->
                            if (forbidden.any { it.containsMatchIn(line) }) {
                                violations += "${file.relativeTo(repoRoot)}:${index + 1}: $line"
                            }
                        }
                }
        }

        assertTrue(
            violations.isEmpty(),
            "Legacy configJson protobuf mapper usage found:\n${violations.joinToString("\n")}"
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
