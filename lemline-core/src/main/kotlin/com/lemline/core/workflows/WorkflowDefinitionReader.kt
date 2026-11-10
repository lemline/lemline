// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.Workflow

/** Validates against the SDK's DSL schema without eagerly expanding its recursive references. */
internal object WorkflowDefinitionReader {
    private val schema by lazy {
        val resource = Thread.currentThread().contextClassLoader.getResourceAsStream("schema/workflow.yaml")
        requireNotNull(resource) { "Serverless Workflow schema/workflow.yaml is missing" }
        resource.use {
            JsonSchemaFactory.getInstance(VersionFlag.V7).getSchema(
                it,
                InputFormat.YAML,
                SchemaValidatorsConfig.builder().preloadJsonSchema(false).build(),
            )
        }
    }

    fun read(definition: String, format: WorkflowFormat): Workflow {
        val mapper = format.mapper()
        val document = mapper.readValue(definition, JsonNode::class.java)
        val errors = schema.validate(document)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        return mapper.convertValue(document, Workflow::class.java)
    }
}
