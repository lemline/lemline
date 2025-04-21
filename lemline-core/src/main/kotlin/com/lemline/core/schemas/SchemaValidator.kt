// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.schemas

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.core.json.LemlineJson
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion.VersionFlag
import com.networknt.schema.ValidationMessage
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.impl.json.JsonUtils
import io.serverlessworkflow.impl.resources.DefaultResourceLoaderFactory
import kotlinx.serialization.json.JsonElement
import java.util.function.Consumer

object SchemaValidator {
    private val resourceLoader = DefaultResourceLoaderFactory.get().getResourceLoader(null)
    private val jsonSchemaFactory = JsonSchemaFactory.getInstance(VersionFlag.V7)

    fun validate(node: JsonElement, schemaUnion: SchemaUnion) =
        validateSchema(with(LemlineJson) { node.toJsonNode() }, schemaUnionToSchema(schemaUnion))

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
        val report = jsonSchemaFactory.getSchema(schema).validate(node)
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
