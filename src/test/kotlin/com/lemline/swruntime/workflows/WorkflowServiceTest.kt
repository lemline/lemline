package com.lemline.swruntime.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.lemline.swruntime.models.WorkflowDefinition
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.system.System
import io.mockk.*
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class WorkflowServiceTest {
    // get a random workflow
    private val workflowPath = "/examples/do-single.yaml"
    private val workflow = loadWorkflowFromYaml(workflowPath)
    private val workflowName = workflow.document.name
    private val workflowVersion = workflow.document.version
    private val workflowDefinition = WorkflowDefinition().apply {
        name = workflowName
        version = workflowVersion
        definition = load(workflowPath)
    }

    private val mockedUse = mockk<Use>()
    private val workflowWithMockedUse = spyk(workflow).apply {
        every { use } returns mockedUse
    }

    // create a mocked repository
    private val mockedRepository = mockk<WorkflowDefinitionRepository>().apply {
        every { findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
    }

    // apply it to the WorkflowService instance
    private val workflowService = WorkflowService(mockedRepository)

    private val objectMapper = ObjectMapper(YAMLFactory())

    @BeforeEach
    fun setUp() {
        clearMocks(mockedRepository, workflowWithMockedUse, mockedUse, answers = false)
        // clear cache
        clearCaches()
        // restore System env variables
        System.restoreEnv()
    }

    @Test
    fun `test getWorkflow uses cache`() {
        // Call the method twice to ensure cache usage
        workflowService.getWorkflow(workflowName, workflowVersion)
        workflowService.getWorkflow(workflowName, workflowVersion)

        // Verify that the repository method was only called once
        verify(exactly = 1) { mockedRepository.findByNameAndVersion(workflowName, workflowVersion) }
    }

    @Test
    fun `test getWorkflow returns workflow when found`() {
        val result = workflowService.getWorkflow(workflowName, workflowVersion)

        assertEquals(workflow.document.name, result.document.name)
        assertEquals(workflow.document.version, result.document.version)
    }


    @Test
    fun `test getWorkflow throws exception when not found`() {
        val nonExistentWorkflowName = "nonExistentWorkflow"
        every { mockedRepository.findByNameAndVersion(nonExistentWorkflowName, workflowVersion) }
            .returns(null)

        val exception = assertThrows<IllegalArgumentException> {
            workflowService.getWorkflow(nonExistentWorkflowName, workflowVersion)
        }

        assertEquals("Workflow $nonExistentWorkflowName:$workflowVersion not found", exception.message)
    }

    @Test
    fun `getSecrets should parse JSON string values`() {
        // Given
        val jsonValue = """{"key": "value"}"""
        val secretName = "test-secret"
        every { mockedUse.secrets } returns listOf(secretName)

        // When
        System.setEnv(secretName, jsonValue)
        val result = workflowService.getSecrets(workflowWithMockedUse)

        // Then
        assertEquals("value", result[secretName]?.get("key")?.asText())
    }

    @Test
    fun `getSecrets should handle plain text values`() {
        // Given
        val plainValue = "plain-text"
        val secretName = "test-secret"
        every { mockedUse.secrets } returns listOf(secretName)

        // When
        System.setEnv(secretName, plainValue)
        val result = workflowService.getSecrets(workflowWithMockedUse)

        // Then
        assertEquals(plainValue, result[secretName]?.asText())
    }

    @Test
    fun `getSecrets should throw when secret is missing`() {
        // Given
        val secretName = "missing-secret"
        every { mockedUse.secrets } returns listOf(secretName)

        // When/Then
        val exception = assertThrows<IllegalStateException> {
            workflowService.getSecrets(workflowWithMockedUse)
        }
        assertEquals("Required secret 'missing-secret' not found in environment variables", exception.message)
    }

    @Test
    fun `getSecrets should return empty map when no secrets defined`() {
        // Given
        every { mockedUse.secrets } returns null

        // When
        val result = workflowService.getSecrets(workflowWithMockedUse)

        // Then
        assertEquals(0, result.size)
    }

    @Test
    fun `getScopedTasks should return empty map for root position`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do"
        val mockPositions = mapOf(
            "/do/0/call0" to CallHTTP(),
            "/do/1/call1" to CallGRPC(),
            "/do/2/call2" to CallOpenAPI()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertTrue(result.isEmpty(), "Should return empty map for root position")
    }

    @Test
    fun `getScopedTasks should return empty map for invalid position`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/invalid/position"
        val mockPositions = mapOf(
            "/do/0/call0" to CallHTTP(),
            "/do/1/call1" to CallGRPC(),
            "/do/2/call2" to CallOpenAPI()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertTrue(result.isEmpty(), "Should return empty map for invalid position")
    }

    @Test
    fun `getScopedTasks should return tasks at same scope level`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do/0/call0"
        val mockPositions = mapOf(
            "/do/0/call0" to CallHTTP(),
            "/do/1/call1" to CallGRPC(),
            "/do/2/call2" to CallOpenAPI()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertEquals(3, result.size, "Should return all tasks at the same scope level")
        assertEquals(CallHTTP::class.java, result[0]?.javaClass, "First task should be CallHTTP")
        assertEquals(CallGRPC::class.java, result[1]?.javaClass, "Second task should be CallGRPC")
        assertEquals(CallOpenAPI::class.java, result[2]?.javaClass, "Third task should be CallOpenAPI")
    }

    @Test
    fun `getScopedTasks should handle nested scopes correctly`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do/0/do/1/call1"
        val mockPositions = mapOf(
            "/do/0/do/0/call0" to CallHTTP(),
            "/do/0/do/1/call1" to CallGRPC(),
            "/do/0/do/2/call2" to CallOpenAPI()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertEquals(3, result.size, "Should return only tasks at the same nested scope level")
        assertEquals(CallHTTP::class.java, result[0]?.javaClass, "First task should be CallHTTP")
        assertEquals(CallGRPC::class.java, result[1]?.javaClass, "Second task should be CallGRPC")
        assertEquals(CallOpenAPI::class.java, result[2]?.javaClass, "Third task should be CallOpenAPI")
    }

    @Test
    fun `getScopedTasks should not include other branches`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do/0/do/1/call1"
        val mockPositions = mapOf(
            "/do/0/do/0/call0" to CallHTTP(),
            "/do/1/do/1/call1" to CallGRPC(),
            "/do/2/do/2/call2" to CallOpenAPI()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertEquals(1, result.size, "Should return only tasks at the same nested scope level")
        assertEquals(CallHTTP::class.java, result[0]?.javaClass, "First task should be CallHTTP")
    }

    @Test
    fun `getScopedTasks should not include deeper tasks`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do/0/do/1/call1"
        val mockPositions = mapOf(
            "/do/0/do/0/call0" to CallHTTP(),
            "/do/0/do/1/call1" to CallGRPC(),
            "/do/0/do/2/call2" to CallOpenAPI(),
            "/do/0/do/2/call2/Other" to CallHTTP()
        )
        every { spiedService.getPositionsCache(workflow) } returns mockPositions

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertEquals(3, result.size, "Should return only tasks at the same nested scope level")
        assertEquals(CallHTTP::class.java, result[0]?.javaClass, "First task should be CallHTTP")
        assertEquals(CallGRPC::class.java, result[1]?.javaClass, "Second task should be CallGRPC")
        assertEquals(CallOpenAPI::class.java, result[2]?.javaClass, "Third task should be CallOpenAPI")
    }

    @Test
    fun `getScopedTasks should handle empty positions cache`() {
        // Given
        val spiedService = spyk(workflowService)
        val position = "/do/0"
        every { spiedService.getPositionsCache(workflow) } returns emptyMap()

        // When
        val result = spiedService.getScopedTasks(workflow, position)

        // Then
        assertTrue(result.isEmpty(), "Should return empty map when positions cache is empty")
    }

    @Test
    fun `test parsePositions against YAML workflow`() {
        // Get all YAML files from examples directory
        val exampleFiles = getResourceFiles("/examples")

        assertTrue(exampleFiles.isNotEmpty(), "No YAML files found in /examples directory")

        exampleFiles.forEach { file ->
            clearCaches()
            println("Testing workflow file: ${file.name}")

            // Load workflow from YAML
            val workflow = loadWorkflowFromYaml("/examples/${file.name}")

            // Parse positions
            workflowService.parseWorkflow(workflow)

            // get task positions
            val positions = WorkflowService.taskPositionsCache[workflow.document.name to workflow.document.version]!!

            // Convert workflow to JSON for comparison
            val workflowJson = objectMapper.valueToTree<ObjectNode>(workflow)

            // Verify each position exists in the JSON
            println("Found ${positions.size} positions in ${file.name}:")
            positions.forEach { (pointer, task) ->
                println("$pointer => ${task.javaClass.simpleName}")
                val jsonNode = findNodeByPointer(workflowJson, pointer)
                assertNotNull(jsonNode, "Position $pointer not found in workflow JSON in file ${file.name}")
                val inFile = "In file ${file.name}"
                println("$pointer => $jsonNode")
                when (task) {
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
                    else -> fail("Unknown task type ${task.javaClass.name} $inFile")
                }
            }

            // Verify all tasks in JSON have corresponding positions
            verifyAllTasksHavePositions(workflowJson, positions, file.name)
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

    private fun loadWorkflowFromYaml(resourcePath: String): Workflow {
        val yamlContent = load(resourcePath)
        return validation().read(yamlContent, WorkflowFormat.YAML)
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

    private fun clearCache(prop: String) {
        WorkflowService::class.java.getDeclaredField(prop).apply {
            isAccessible = true
            (get(workflowService) as MutableMap<*, *>).clear()
        }
    }

    private fun clearCaches() {
        clearCache("workflowCache")
        clearCache("taskPositionsCache")
        clearCache("secretsCache")
    }
}
