package com.lemline.core.workflows

//import com.lemline.core.load
//import com.lemline.core.loadWorkflowFromYaml
//import com.lemline.worker.repositories.WorkflowDefinitionRepository
//import io.mockk.*
//import io.serverlessworkflow.api.types.Use
//import io.serverlessworkflow.impl.WorkflowDefinition
//import kotlinx.serialization.json.JsonPrimitive
//import org.junit.jupiter.api.Assertions.assertEquals
//import org.junit.jupiter.api.BeforeEach
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.assertThrows
//
//class WorkflowParserTest {
//    // get a random workflow
//    private val workflowPath = "/examples/do-single.yaml"
//    private val workflow = loadWorkflowFromYaml(workflowPath)
//    private val workflowName = workflow.document.name
//    private val workflowVersion = workflow.document.version
//    private val workflowDefinition = WorkflowDefinition().apply {
//        name = workflowName
//        version = workflowVersion
//        definition = load(workflowPath)
//    }
//
//    private val mockedUse = mockk<Use>()
//    private val workflowWithMockedUse = spyk(workflow).apply {
//        every { use } returns mockedUse
//    }
//
//    // create a mocked repository
//    private val mockedRepository = mockk<WorkflowDefinitionRepository>().apply {
//        every { findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
//    }
//
//    // apply it to the WorkflowService initialPosition
//    private val workflowParser = WorkflowParser(mockedRepository)
//
//    @BeforeEach
//    fun setUp() {
//        clearMocks(mockedRepository, workflowWithMockedUse, mockedUse, answers = false)
//        // clear cache
//        clearCaches()
//        // restore System env variables
//        System.restoreEnv()
//    }
//
//    @Test
//    fun `test getWorkflow uses cache`() {
//        // Call the method twice to ensure cache usage
//        Workflows.getOrNull(workflowName, workflowVersion)
//        Workflows.getOrNull(workflowName, workflowVersion)
//
//        // Verify that the repository method was only called once
//        verify(exactly = 1) { mockedRepository.findByNameAndVersion(workflowName, workflowVersion) }
//    }
//
//    @Test
//    fun `test getWorkflow returns workflow when found`() {
//        val result = workflowParser.getWorkflow(workflowName, workflowVersion)
//
//        assertEquals(workflow.document.name, result.document.name)
//        assertEquals(workflow.document.version, result.document.version)
//    }
//
//
//    @Test
//    fun `test getWorkflow throws exception when not found`() {
//        val nonExistentWorkflowName = "nonExistentWorkflow"
//        every { mockedRepository.findByNameAndVersion(nonExistentWorkflowName, workflowVersion) }
//            .returns(null)
//
//        val exception = assertThrows<IllegalStateException> {
//            workflowParser.getWorkflow(nonExistentWorkflowName, workflowVersion)
//        }
//
//        assertEquals("Workflow $nonExistentWorkflowName:$workflowVersion not found", exception.message)
//    }
//
//    @Test
//    fun `getSecrets should parse JSON string values`() {
//        // Given
//        val jsonValue = """{"key": "value"}"""
//        val secretName = "test-secret"
//        every { mockedUse.secrets } returns listOf(secretName)
//
//        // When
//        System.setEnv(secretName, jsonValue)
//        val result = workflowParser.getSecrets(workflowWithMockedUse)
//
//        // Then
//        assertEquals(
//            LemlineJson.encodeToElement(mapOf("key" to "value")),
//            result[secretName]
//        )
//    }
//
//    @Test
//    fun `getSecrets should handle plain text values`() {
//        // Given
//        val plainValue = "plain-text"
//        val secretName = "test-secret"
//        every { mockedUse.secrets } returns listOf(secretName)
//
//        // When
//        System.setEnv(secretName, plainValue)
//        val result = workflowParser.getSecrets(workflowWithMockedUse)
//
//        // Then
//        assertEquals(
//            JsonPrimitive(plainValue),
//            result[secretName]
//        )
//    }
//
//    @Test
//    fun `getSecrets should throw when secret is missing`() {
//        // Given
//        val secretName = "missing-secret"
//        every { mockedUse.secrets } returns listOf(secretName)
//
//        // When/Then
//        val exception = assertThrows<IllegalStateException> {
//            workflowParser.getSecrets(workflowWithMockedUse)
//        }
//        assertEquals("Required secret 'missing-secret' not found in environment variables", exception.message)
//    }
//
//    @Test
//    fun `getSecrets should return empty map when no secrets defined`() {
//        // Given
//        every { mockedUse.secrets } returns null
//
//        // When
//        val result = workflowParser.getSecrets(workflowWithMockedUse)
//
//        // Then
//        assertEquals(0, result.size)
//    }
//
//    private fun clearCache(prop: String) {
//        WorkflowParser::class.java.getDeclaredField(prop).apply {
//            isAccessible = true
//            (get(workflowParser) as MutableMap<*, *>).clear()
//        }
//    }
//
//    private fun clearCaches() {
//        clearCache("workflowCache")
//        clearCache("secretsCache")
//    }
//}
