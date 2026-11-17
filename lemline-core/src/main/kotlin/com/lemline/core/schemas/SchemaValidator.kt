// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.schemas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLParser
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.SchemaUnion
import io.serverlessworkflow.impl.resources.DefaultResourceLoaderFactory
import java.io.InputStream
import java.net.URI

object SchemaValidator {
    // Schema property names in arrays (for example required: [on]) are strings,
    // not YAML 1.1 boolean aliases. Actual true/false schema keywords stay booleans.
    private val yamlSchemaMapper = YAMLMapper.builder()
        .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS)
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build()

    internal fun readSchema(input: InputStream, format: WorkflowFormat): JsonNode =
        (if (format == WorkflowFormat.YAML) yamlSchemaMapper else WorkflowFormat.JSON.mapper()).reader()
            .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(input).normalizeRuntimeTree()

    private val schemaReader = com.networknt.schema.serialization.NodeReader.builder()
        .jsonMapper(LemlineJson.jacksonMapper).yamlMapper(yamlSchemaMapper).build()
    private val boundedSchemaReader = object : com.networknt.schema.serialization.NodeReader {
        override fun readTree(content: String, inputFormat: com.networknt.schema.InputFormat): JsonNode =
            schemaReader.readTree(content, inputFormat).also(::validateDecimalBounds)
        override fun readTree(content: InputStream, inputFormat: com.networknt.schema.InputFormat): JsonNode =
            schemaReader.readTree(content, inputFormat).also(::validateDecimalBounds)
    }

    // Use JSON Schema Draft 2020-12 for modern standards compliance
    private val schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
        it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        // networknt 2 disables external references by default; retain 1.x behavior.
        it.schemaLoader { loader -> loader.fetchRemoteResources() }
        it.nodeReader(boundedSchemaReader)
    }

    fun validate(node: JsonNode, schemaUnion: SchemaUnion) =
        validateSchema(node, schemaUnionToSchema(schemaUnion))

    fun validateJsonNode(node: JsonNode, schema: JsonNode) =
        validateSchema(node.deepCopy<JsonNode>().normalizeRuntimeTree(), schema.deepCopy<JsonNode>().normalizeRuntimeTree())

    internal fun schemaUnionToSchema(
        schemaUnion: SchemaUnion,
        openHttp: (URI) -> InputStream = { it.toURL().openStream() },
    ): JsonNode = when {
        schemaUnion.schemaInline != null ->
            LemlineJson.jacksonMapper.valueToTree<JsonNode>(schemaUnion.schemaInline.document).normalizeRuntimeTree()

        schemaUnion.schemaExternal != null -> {
            // Keep the previous per-validation freshness semantics. The SDK's cache
            // otherwise retains HTTP schemas forever when Last-Modified is absent.
            DefaultResourceLoaderFactory.get().getResourceLoader(null, null).use { loader ->
                val uri = loader.uri(schemaUnion.schemaExternal.resource.endpoint)
                if (uri.scheme?.lowercase() in setOf("http", "https")) {
                    // Fetch the declared URL unchanged. The SDK rewrites GitHub raw URLs
                    // to invalid paths and drops query strings instead of following redirects.
                    openHttp(uri).use { readSchema(it, WorkflowFormat.fromFileName(uri.path)) }
                } else {
                    loader.loadStatic(schemaUnion.schemaExternal.resource) { resource ->
                        resource.open().use { readSchema(it, WorkflowFormat.fromFileName(resource.name())) }
                    }
                }
            }
        }

        else -> throw IllegalArgumentException("Unknown schema type $schemaUnion")
    }

    private fun validateSchema(node: JsonNode, schema: JsonNode) {
        validateDecimalBounds(node)
        validateDecimalBounds(schema)
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

        val jsonSchema = schemaRegistry.getSchema(schema)
        val report = jsonSchema.validate(node)
        if (report.isNotEmpty()) {
            val sb = StringBuilder("There are JsonSchema validation errors:")
            report.forEach { sb.append(System.lineSeparator()).append(it.message) }
            throw IllegalArgumentException(sb.toString())
        }
    }
}
