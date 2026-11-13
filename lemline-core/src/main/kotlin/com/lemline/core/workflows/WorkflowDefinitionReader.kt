// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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

    fun read(definition: String, format: WorkflowFormat, validate: Boolean = true): Workflow {
        val mapper = format.mapper()
        val document = mapper.readValue(definition, JsonNode::class.java) as? ObjectNode
            ?: throw IllegalArgumentException("Workflow definition must be an object")
        val evaluate = document.remove("evaluate")
        validateEvaluate(evaluate)
        if (validate) {
            val errors = schema.validate(document)
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
        // Avoid re-serializing the tree: the SDK mapper omits empty arrays, which would
        // turn an explicit `any: []` wildcard into a different consumption strategy.
        return mapper.treeToValue(document, EvaluatedWorkflow::class.java).apply { this.evaluate = evaluate }
    }

    private fun validateEvaluate(evaluate: JsonNode?) {
        if (evaluate == null) return
        require(evaluate.isObject) { "evaluate must be an object" }
        require(evaluate.fieldNames().asSequence().all { it == "language" || it == "mode" }) {
            "evaluate supports only language and mode"
        }
        evaluate.get("language")?.let { require(it.isTextual && it.textValue() == "jq") { "Unsupported evaluate.language: $it" } }
        evaluate.get("mode")?.let {
            require(it.isTextual && it.textValue() in setOf("strict", "loose")) { "Invalid evaluate.mode: $it" }
        }
    }
}
