// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson
import com.lemline.core.schemas.SchemaValidator
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.Workflow

/** Validates against the SDK's DSL schema without eagerly expanding its recursive references. */
internal object WorkflowDefinitionReader {
    private val schema by lazy {
        val resource = Thread.currentThread().contextClassLoader.getResourceAsStream("schema/workflow.yaml")
        requireNotNull(resource) { "Open Workflow schema/workflow.yaml is missing" }
        resource.use {
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) { builder ->
                builder.schemaRegistryConfig(SchemaRegistryConfig.builder().preloadSchema(false).build())
            }.getSchema(SchemaValidator.readSchema(it, WorkflowFormat.YAML))
        }
    }

    fun read(definition: String, format: WorkflowFormat, validate: Boolean = true): Workflow {
        val mapper = format.mapper()
        val document = mapper.readerFor(JsonNode::class.java)
            .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readValue<JsonNode>(definition) as? ObjectNode
            ?: throw IllegalArgumentException("Workflow definition must be an object")
        com.lemline.common.json.validateDecimalBounds(document)
        val evaluate = document.get("evaluate")
        validateEvaluate(evaluate)
        if (validate) {
            val errors = schema.validate(document)
            require(errors.isEmpty()) { errors.joinToString("\n") }
        }
        // Avoid re-serializing the tree: the SDK mapper omits empty arrays, which would
        // turn an explicit `any: []` wildcard into a different consumption strategy.
        return LemlineJson.jacksonMapper.treeToValue(document, Workflow::class.java).also { workflow ->
            // Timer schedules are registered before any workflow input/scope exists.
            require(workflow.schedule?.every?.durationExpression == null) { "schedule.every must be a static duration" }
            require(workflow.schedule?.after?.durationExpression == null) { "schedule.after must be a static duration" }
        }
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
