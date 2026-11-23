// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import io.serverlessworkflow.api.WorkflowFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class DocumentationWorkflowTest {
    @TestFactory
    fun `documentation workflows load in Lemline`(): List<DynamicTest> {
        val directory = File(System.getProperty("lemline.docs.workflows", "../lemline-docs/snippets/workflows"))
        val root = directory.toPath().toAbsolutePath().normalize()
        check(root.toRealPath() == root.parent.parent.toRealPath().resolve("snippets/workflows")) {
            "Documentation workflow directory must not use symbolic links: $directory"
        }
        val workflows = Files.walk(root).use { paths ->
            paths.filter { path ->
                check(!Files.isSymbolicLink(path)) { "Symbolic link in documentation workflow directory: $path" }
                Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.toFile().extension in setOf("yaml", "yml", "json")
            }.sorted().map { it.toFile() }.toList()
        }
        check(workflows.isNotEmpty()) { "No documentation workflows in $directory" }

        return workflows.map { file ->
            DynamicTest.dynamicTest(file.relativeTo(root.toFile()).invariantSeparatorsPath) {
                val format = if (file.extension == "json") WorkflowFormat.JSON else WorkflowFormat.YAML
                WorkflowDefinitionReader.read(file.readText(), format)
            }
        }
    }
}
