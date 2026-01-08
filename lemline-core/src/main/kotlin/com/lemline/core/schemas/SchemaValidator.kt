// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.schemas

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.LemlineJson
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.ValidationMessage
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.impl.json.JsonUtils
import io.serverlessworkflow.impl.resources.DefaultResourceLoaderFactory
import java.util.function.Consumer
import kotlinx.serialization.json.JsonElement

object SchemaValidator {
    private val resourceLoader = DefaultResourceLoaderFactory.get().getResourceLoader(null)

    // Use JSON Schema Draft 2020-12 for modern standards compliance
    private val jsonSchemaFactory = JsonSchemaFactory.getInstance(VersionFlag.V202012)
    private val schemaConfig = SchemaValidatorsConfig.builder().build()

    fun validate(node: JsonElement, schemaUnion: SchemaUnion) =
        validateSchema(with(LemlineJson) { node.toJsonNode() }, schemaUnionToSchema(schemaUnion))

    fun validateJsonNode(node: JsonNode, schema: JsonNode) = validateSchema(node, schema)

    private fun schemaUnionToSchema(schemaUnion: SchemaUnion): JsonNode = when {
        schemaUnion.schemaInline != null ->
            JsonUtils.mapper().convertValue(schemaUnion.schemaInline.document, JsonNode::class.java)

        schemaUnion.schemaExternal != null -> {
            val resource = resourceLoader.loadStatic(schemaUnion.schemaExternal.resource)
            WorkflowFormat.fromFileName(resource.name()).mapper().readTree(resource.open())
        }

        else -> throw IllegalArgumentException("Unknown schema type $schemaUnion")
    }

    private fun validateSchema(node: JsonNode, schema: JsonNode) {
        // Early check for simple 'required' fields to provide clearer error messages
        if (schema.has("required") && node.isObject) {
            val required = schema["required"].mapNotNull { it.asText(null) }
            if (required.isNotEmpty()) {
                val present = node.fieldNames().asSequence().toSet()
                val missing = required.filterNot { it in present }
                if (missing.isNotEmpty()) {
                    val missingMsg = missing.joinToString(", ") { "'$it'" }
                    throw IllegalArgumentException("There are JsonSchema validation errors:\nMissing required field(s): $missingMsg")
                }
            }
        }

        val jsonSchema = jsonSchemaFactory.getSchema(schema, schemaConfig)
        // Enable format assertions (disabled by default in Draft 2020-12)
        val report = jsonSchema.validate(node) { executionContext ->
            executionContext.executionConfig.formatAssertionsEnabled = true
        }
        if (report.isNotEmpty()) {
            val sb = StringBuilder("There are JsonSchema validation errors:")
            report.forEach(
                Consumer { m: ValidationMessage ->
                    sb.append(System.lineSeparator()).append(m.message)
                },
            )
            throw IllegalArgumentException(sb.toString())
        }
    }
}
