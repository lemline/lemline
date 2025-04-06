package com.lemline.sw

import com.lemline.common.json.Json
import com.lemline.sw.workflows.WorkflowInstance
import com.lemline.sw.workflows.WorkflowParser
import com.lemline.sw.workflows.WorkflowParserTest
import com.lemline.worker.messaging.WorkflowMessage
import com.lemline.worker.models.WorkflowDefinition
import com.lemline.worker.repositories.WorkflowDefinitionRepository
import io.mockk.every
import io.mockk.mockk
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal inline fun <reified T> JsonObject.set(key: String, value: T) =
    JsonObject(toMutableMap().apply { set(key, Json.encodeToElement(value)) })

internal fun load(resourcePath: String): String {
    val inputStream = WorkflowParserTest::class.java.getResourceAsStream(resourcePath)
        ?: throw IllegalArgumentException("Resource not found: $resourcePath")

    return inputStream.bufferedReader().use { it.readText() }
}

internal fun loadWorkflowFromYaml(resourcePath: String): Workflow {
    val yamlContent = load(resourcePath)
    return validation().read(yamlContent, WorkflowFormat.YAML)
}

internal fun getWorkflowInstance(doYaml: String, input: JsonElement): WorkflowInstance {
    val hash = doYaml.hashCode()
    val document = """
            document:
              dsl: '1.0.0'
              namespace: test
              name: do-nested-$hash
              version: '0.1.0'
        """.trimIndent()
    val workflowYaml = document + "\n" + doYaml.trimIndent().replace("@", "$")
    val workflow = validation().read(workflowYaml, WorkflowFormat.YAML)
    val workflowName = workflow.document.name
    val workflowVersion = workflow.document.version
    val workflowDefinition = WorkflowDefinition().apply {
        name = workflowName
        version = workflowVersion
        definition = workflowYaml
    }

    // create a mocked repository
    val mockedRepository = mockk<WorkflowDefinitionRepository>().apply {
        every { findByNameAndVersion(workflowName, workflowVersion) } returns workflowDefinition
    }

    // apply it to the WorkflowService initialPosition
    val mockedService = WorkflowParser(mockedRepository)

    val msg = WorkflowMessage.newInstance(workflowName, workflowVersion, "testId", input)

    return WorkflowInstance(
        name = msg.name,
        version = msg.version,
        initialStates = msg.states,
        initialPosition = msg.position
    ).apply { workflowParser = mockedService }
}