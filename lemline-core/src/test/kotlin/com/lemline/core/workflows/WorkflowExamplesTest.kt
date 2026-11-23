// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import io.serverlessworkflow.api.WorkflowFormat
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class WorkflowExamplesTest {
    @TestFactory
    fun `workflow examples conform to DSL 1_0_3`(): List<DynamicTest> =
        System.getProperty("lemline.test.exampleDirectories").split(File.pathSeparator).flatMap { directory ->
            val files = File(directory).walkTopDown()
                .filter { it.isFile && it.extension in setOf("yaml", "yml", "json") }
                .sortedBy { it.path }
                .toList()
            check(files.isNotEmpty()) { "No workflow examples found in $directory" }
            files.map { file ->
                DynamicTest.dynamicTest(file.path) {
                    val format = if (file.extension == "json") WorkflowFormat.JSON else WorkflowFormat.YAML
                    val workflow = WorkflowDefinitionReader.read(file.readText(), format)
                    assertEquals("1.0.3", workflow.document.dsl)
                }
            }
        }
}
