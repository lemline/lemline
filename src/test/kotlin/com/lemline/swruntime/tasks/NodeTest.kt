package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.workflows.WorkflowParser
import com.lemline.swruntime.workflows.index
import io.mockk.mockk
import io.serverlessworkflow.api.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class NodeTest {
    // WorkflowService instance
    private val mockedRepository = mockk<WorkflowDefinitionRepository>()
    private val workflowParser = WorkflowParser(mockedRepository)
    private val objectMapper = ObjectMapper(YAMLFactory())

    @Test
    fun `test parsePositions against YAML workflow`() {
        // Get all YAML files from examples directory
        val exampleFiles = getResourceFiles("/examples")

        assertTrue(exampleFiles.isNotEmpty(), "No YAML files found in /examples directory")

        exampleFiles.forEach { file ->
            println("Testing workflow file: ${file.name}")

            // Load workflow from YAML
            val definition = load("/examples/${file.name}")
            val workflow = workflowParser.parseWorkflow(definition)

            // get position<>TaskNode map
            val positions = WorkflowParser.nodesCache[workflow.index]!!

            // Convert workflow to JSON for comparison
            val workflowJson = objectMapper.valueToTree<ObjectNode>(workflow)

            // Verify each position exists in the JSON
            println("Found ${positions.size} positions in ${file.name}:")
            positions.forEach { (pointer, taskNode) ->
                println("$pointer => ${taskNode.task.javaClass.simpleName}")
                val jsonNode = findNodeByPointer(workflowJson, pointer.toString())
                println("jsonNode = $workflowJson, pointer = $pointer")
                assertNotNull(jsonNode, "Position $pointer not found in workflow JSON in file ${file.name}")
                println(println(workflowJson.at("")))
                val inFile = "In file ${file.name}"
                println("$pointer => $jsonNode")
                when (taskNode.task) {
                    is RootTask ->
                        assertTrue(jsonNode!!.get("document").isObject, inFile)

                    is CallOpenAPI -> assertEquals(jsonNode!!.get("call").textValue(), "openapi", inFile)
                    is CallHTTP -> assertEquals(jsonNode!!.get("call").textValue(), "http", inFile)
                    is CallGRPC -> assertEquals(jsonNode!!.get("call").textValue(), "grpc", inFile)
                    is CallAsyncAPI -> assertEquals(jsonNode!!.get("call").textValue(), "asyncapi", inFile)
                    is CallFunction -> assertTrue(jsonNode!!.get("call").isTextual, inFile)
                    is EmitTask -> assertTrue(jsonNode!!.get("emit").isObject, inFile)
                    is DoTask -> assertTrue(jsonNode!!.isArray || jsonNode.has("do"), inFile)
                    is ForTask -> assertTrue(jsonNode!!.has("for"), inFile)
                    is ForkTask -> assertTrue(jsonNode!!.has("fork"), inFile)
                    is ListenTask -> assertTrue(jsonNode!!.has("listen"), inFile)
                    is RaiseTask -> assertTrue(jsonNode!!.has("raise"), inFile)
                    is RunTask -> assertTrue(jsonNode!!.has("run"), inFile)
                    is SetTask -> assertTrue(jsonNode!!.has("set"), inFile)
                    is SwitchTask -> assertTrue(jsonNode!!.has("switch"), inFile)
                    is TryTask -> assertTrue(jsonNode!!.has("try"), inFile)
                    is WaitTask -> assertTrue(jsonNode!!.has("wait"), inFile)
                    else -> fail("Unknown task type ${taskNode.task.javaClass.name} $inFile")
                }
            }

            // Verify all tasks in JSON have corresponding positions
            verifyAllTasksHavePositions(workflowJson, positions.mapKeys { it.key.toString() }, file.name)
        }
    }

    private fun getResourceFiles(path: String): List<File> = javaClass.getResource(path)?.let { url ->
        File(url.toURI()).listFiles { file ->
            file.name.endsWith(".yaml") || file.name.endsWith(".yml")
        }?.toList() ?: emptyList()
    } ?: emptyList()

    private fun load(resourcePath: String): String {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun findNodeByPointer(root: JsonNode, pointer: String): JsonNode? = try {
        root.at(pointer)
    } catch (e: IllegalArgumentException) {
        null
    }

    private val noDoTaskTypes = listOf(
        "call",
        "emit",
        "for",
        "fork",
        "listen",
        "raise",
        "run",
        "set",
        "switch",
        "try",
        "wait"
    )

    private fun verifyAllTasksHavePositions(json: JsonNode, positions: Map<String, Any>, fileName: String) {

        fun verifyNode(node: JsonNode, currentPath: String) {
            if (node.isObject) {
                if (node.isNoDoTask()) {
                    assertTrue(
                        positions.containsKey(currentPath),
                        "Task at position $currentPath not found in positions map in file $fileName"
                    )
                }
                node.get("do")?.let {
                    assertTrue(
                        positions.containsKey("$currentPath/do"),
                        "Task at position $currentPath/do not found in positions map in file $fileName"
                    )
                }
                // Recursively check all fields
                node.fields().forEach { (field, value) ->
                    verifyNode(value, "$currentPath/$field")
                }
            } else if (node.isArray) {
                node.forEachIndexed { index, element ->
                    verifyNode(element, "$currentPath/$index")
                }
            }
        }

        val doNode = findNodeByPointer(json, "/do")
        assertNotNull(doNode, "No /do node found in workflow in file $fileName")
        verifyNode(doNode!!, "/do")
    }

    // Check if this node represents a task (excluding Do tasks)
    private fun JsonNode.isNoDoTask(): Boolean =
        fieldNames().asSequence().any { it in noDoTaskTypes } && !isForDuration()

    // Special case when the "for" keyword is used for specifying a duration
    private fun JsonNode.isForDuration(): Boolean {
        // if also another task
        if (fieldNames().asSequence().count { it in noDoTaskTypes } > 1) return false
        // if not for
        val forNode = get("for") ?: return false
        // if for
        return forNode.fieldNames().asSequence()
            .any { it.lowercase() in listOf("days", "hours", "minutes", "seconds", "milliseconds") }
    }
}
