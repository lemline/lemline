// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core

import com.lemline.common.json.LemlineJson
import com.lemline.core.definitions.Definitions
import com.lemline.core.processor.Processor
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.WorkflowReader.validation
import io.serverlessworkflow.api.types.Workflow
import java.util.*
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

inline fun <reified T> JsonObject.set(key: String, value: T) =
    JsonObject(toMutableMap().apply { set(key, LemlineJson.encodeToElement(value)) })

internal fun load(resourcePath: String): String {
    val inputStream = Definitions::class.java.getResourceAsStream(resourcePath)
        ?: throw IllegalArgumentException("Resource not found: $resourcePath")

    return inputStream.bufferedReader().use { it.readText() }
}

internal fun loadWorkflowFromYaml(resourcePath: String): Workflow {
    val yamlContent = load(resourcePath)
    return validation().read(yamlContent, WorkflowFormat.YAML)
}

@ExperimentalTime
internal fun getWorkflowProcessor(
    doYaml: String,
    input: JsonElement,
    name: String = "workflow-${doYaml.hashCode()}",
    version: String = "0.1.0",
    id: UUID = UUID.randomUUID(),
): Processor {
    val document =
        """document:
              dsl: '1.0.0'
              namespace: test
              name: $name
              version: $version
        """.trimIndent()
    val workflowYaml = document + "\n" + doYaml.trimIndent().replace("@", "$")
    Definitions.parseAndPut(workflowYaml)

    return Processor.createNew(
        name = name,
        version = version,
        id = id,
        rawInput = input,
    )
}
