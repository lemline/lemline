package com.lemline.swruntime.workflows

import com.lemline.swruntime.messaging.WorkflowExecutionMessage
import com.lemline.swruntime.models.WorkflowDefinition
import com.lemline.swruntime.repositories.WorkflowDefinitionRepository
import com.lemline.swruntime.system.System
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Use
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.json.JsonUtils
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WorkflowInstanceTest {
    // get a random workflow
    private val workflowPath = "/examples/do-nested.yaml"
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
    private val mockedService = WorkflowService(mockedRepository)

    @BeforeEach
    fun setUp() {
        clearMocks(mockedRepository, workflowWithMockedUse, mockedUse, answers = false)
        // clear cache
        clearCaches()
        // restore System env variables
        System.restoreEnv()
    }

    @Test
    fun `test getWorkflow uses cache`() = runBlocking {
        val msg = WorkflowExecutionMessage.create(
            workflowName, workflowVersion, "testId", JsonUtils.`object`()
        )

        val instance = WorkflowInstance(
            name = msg.name,
            version = msg.version,
            state = msg.state.mapKeys { state -> state.key.toPosition() }.toMutableMap(),
            position = msg.position.toPosition()
        ).apply { workflowService = mockedService }

        do {
            instance.run()
            println(instance.position.jsonPointer)
        } while (!instance.isCompleted())
    }


    private fun load(resourcePath: String): String {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")

        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun loadWorkflowFromYaml(resourcePath: String): Workflow {
        val yamlContent = load(resourcePath)
        return validation().read(yamlContent, WorkflowFormat.YAML)
    }

    private fun clearCache(prop: String) {
        WorkflowService::class.java.getDeclaredField(prop).apply {
            isAccessible = true
            (get(mockedService) as MutableMap<*, *>).clear()
        }
    }

    private fun clearCaches() {
        clearCache("workflowCache")
        clearCache("secretsCache")
    }
}
