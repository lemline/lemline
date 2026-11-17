// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.schemas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.common.json.*
import com.sun.net.httpserver.HttpServer
import io.serverlessworkflow.api.WorkflowFormat
import io.serverlessworkflow.api.types.SchemaUnion
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class SchemaValidatorTest {

    private val mapper = ObjectMapper()

    @Test
    fun `extreme decimal exponents are rejected before validator expansion`() {
        val schema = parseJson("""{"enum":[0]}""")
        for (value in listOf("1e1000000", "1e-1000000")) {
            val file = Files.createTempFile("lemline-bounded-schema-", ".json")
            try {
                Files.writeString(file, "{\"enum\":[$value]}")
                val reference = LemlineJson.yamlMapper.readValue("format: json\ndocument:\n  '\$ref': '${file.toUri()}'", SchemaUnion::class.java)
                val rejected = assertThrows<Exception> { SchemaValidator.validate(parseJson("0"), reference) }
                kotlin.test.assertTrue(rejected.stackTraceToString().contains("Decimal expansion exceeds"))
            } finally {
                Files.deleteIfExists(file)
            }
            val node = com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.toBigDecimal())
            val failure = assertThrows<IllegalArgumentException> { SchemaValidator.validateJsonNode(node, schema) }
            kotlin.test.assertTrue(failure.message!!.contains("Decimal expansion exceeds"))
            assertThrows<IllegalArgumentException> {
                SchemaValidator.validateJsonNode(parseJson("0"), mapper.createObjectNode().set<JsonNode>("enum", mapper.createArrayNode().add(node)))
            }
        }
    }

    @Test
    fun `nested decimal const and enum match for inline and external schemas`() {
        val data = parseJson("""{"amount":1.234567890123456789}""")
        val different = parseJson("""{"amount":1.234567890123456788}""")
        for (keyword in listOf("const", "enum")) {
            val document = if (keyword == "const") "{\"const\":$data}" else "{\"enum\":[$data]}"
            val inline = LemlineJson.yamlMapper.readValue("format: json\ndocument: $document", SchemaUnion::class.java)
            assertDoesNotThrow { SchemaValidator.validate(data, inline) }
            assertThrows<IllegalArgumentException> { SchemaValidator.validate(different, inline) }
            val file = Files.createTempFile("lemline-decimal-schema-", ".json")
            try {
                Files.writeString(file, document)
                val external = LemlineJson.yamlMapper.readValue("format: json\nresource:\n  endpoint: '${file.toUri()}'", SchemaUnion::class.java)
                assertDoesNotThrow { SchemaValidator.validate(data, external) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(different, external) }
                val reference = LemlineJson.yamlMapper.readValue("format: json\ndocument:\n  '\$ref': '${file.toUri()}'", SchemaUnion::class.java)
                assertDoesNotThrow { SchemaValidator.validate(data, reference) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(different, reference) }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `external GitHub raw schema URLs preserve their declared path and query`() {
        val url = "https://github.com/open-workflow-specification/specification/raw/refs/heads/main/schema/workflow.yaml?download=1"
        val schema = WorkflowFormat.YAML.mapper().readValue("format: json\nresource:\n  endpoint: '$url'\n", SchemaUnion::class.java)
        val loaded = SchemaValidator.schemaUnionToSchema(schema) { requested ->
            assertEquals(url, requested.toString())
            "type: string".byteInputStream()
        }
        assertEquals(mapper.readTree("""{"type":"string"}"""), loaded)
    }

    @Test
    fun `schemas resolve external HTTP and file references`() {
        val content = """{"type":"object","required":["value"],"properties":{"value":{"type":"boolean"}}}"""
        val file = Files.createTempFile("lemline-referenced-schema-", ".json")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/referenced.json") { exchange ->
            exchange.use {
                val bytes = content.toByteArray()
                it.sendResponseHeaders(200, bytes.size.toLong())
                it.responseBody.write(bytes)
            }
        }
        server.start()
        try {
            Files.writeString(file, content)
            for (uri in listOf(file.toUri().toString(), "http://127.0.0.1:${server.address.port}/referenced.json")) {
                val schema = mapper.createObjectNode().put("\$ref", uri)
                assertDoesNotThrow { SchemaValidator.validateJsonNode(mapper.readTree("""{"value":true}"""), schema) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validateJsonNode(mapper.readTree("""{"value":1}"""), schema) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validateJsonNode(mapper.readTree("{}"), schema) }
            }
        } finally {
            server.stop(0)
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `external HTTP schemas refresh without last modified or HEAD support`() {
        for (headStatus in listOf(200, 405)) {
            val content = AtomicReference("""{"type":"object","required":["old"]}""")
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/schema.json") { exchange ->
                exchange.use {
                    if (it.requestMethod == "HEAD") it.sendResponseHeaders(headStatus, -1)
                    else {
                        val bytes = content.get().toByteArray()
                        it.sendResponseHeaders(200, bytes.size.toLong())
                        it.responseBody.write(bytes)
                    }
                }
            }
            server.createContext("/redirect.json") { exchange ->
                exchange.use {
                    it.responseHeaders.add("Location", "/schema.json")
                    it.sendResponseHeaders(302, -1)
                }
            }
            server.start()
            try {
                val schema = WorkflowFormat.YAML.mapper().readValue(
                    "format: json\nresource:\n  endpoint: 'http://127.0.0.1:${server.address.port}/redirect.json'\n", SchemaUnion::class.java,
                )
                val data = parseJson("""{"old":true}""")
                assertDoesNotThrow { SchemaValidator.validate(data, schema) }
                content.set("""{"type":"object","required":["new"]}""")
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(data, schema) }
                assertDoesNotThrow { SchemaValidator.validate(parseJson("""{"new":true}"""), schema) }
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `external JSON and YAML schemas use the SDK resource loader`() {
        for ((suffix, content) in listOf(
            ".json" to """{"type":"object","required":["name","on"],"properties":{"name":{"type":"string"},"on":{"type":"boolean"}},"additionalProperties":false}""",
            ".yaml" to "type: object\nrequired: [name, on]\nproperties:\n  name: { type: string }\n  on: { type: boolean }\nadditionalProperties: false\n",
        )) {
            val file = Files.createTempFile("lemline-schema-", suffix)
            try {
                Files.writeString(file, content)
                val schema = WorkflowFormat.YAML.mapper().readValue(
                    "format: json\nresource:\n  endpoint: '${file.toUri()}'\n", SchemaUnion::class.java,
                )
                assertDoesNotThrow { SchemaValidator.validate(parseJson("""{"name":"valid","on":true}"""), schema) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(parseJson("""{"name":1,"on":true}"""), schema) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(parseJson("""{"name":"valid"}"""), schema) }
                assertThrows<IllegalArgumentException> { SchemaValidator.validate(parseJson("""{"name":"valid","on":true,"extra":1}"""), schema) }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `test validate simple required field - valid`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "required": ["name"],
                "properties": {
                    "name": { "type": "string" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"name": "test"}""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test validate simple required field - missing field`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "required": ["name"],
                "properties": {
                    "name": { "type": "string" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"other": "value"}""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test validate type mismatch`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "properties": {
                    "count": { "type": "integer" }
                }
            }
            """.trimIndent()
        )
        val data = mapper.readTree("""{"count": "not a number"}""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - valid date-time`() {
        // This tests the oneOf validation bug fix
        // Schema exactly matching Open Workflow time field schema
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""2024-01-15T10:30:00Z"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - valid runtime expression`() {
        // This tests the oneOf validation bug fix
        // The buggy validator would fail because date-time format fails first
        // and oneOf doesn't correctly try the pattern alternative
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{.timestamp}"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with date-time format - invalid value`() {
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""not-a-date-or-expression"""")

        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with complex runtime expression`() {
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{ . > \"2024-01-01T00:00:00Z\" }"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test oneOf with contains runtime expression`() {
        // Test the exact expression used in ListenTestCases
        val schema = mapper.readTree(
            """
            {
                "oneOf": [
                    { "type": "string", "format": "date-time" },
                    { "type": "string", "pattern": "^\\s*\\$\\{.+\\}\\s*$" }
                ]
            }
            """.trimIndent()
        )
        val data = mapper.readTree(""""${"$"}{ contains(\"2024\") }"""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(data, schema)
        }
    }

    @Test
    fun `test nested object validation`() {
        val schema = mapper.readTree(
            """
            {
                "type": "object",
                "properties": {
                    "user": {
                        "type": "object",
                        "required": ["id"],
                        "properties": {
                            "id": { "type": "integer" },
                            "name": { "type": "string" }
                        }
                    }
                }
            }
            """.trimIndent()
        )
        val validData = mapper.readTree("""{"user": {"id": 123, "name": "test"}}""")
        val invalidData = mapper.readTree("""{"user": {"name": "test"}}""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(validData, schema)
        }
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(invalidData, schema)
        }
    }

    @Test
    fun `test array validation`() {
        val schema = mapper.readTree(
            """
            {
                "type": "array",
                "items": { "type": "string" }
            }
            """.trimIndent()
        )
        val validData = mapper.readTree("""["a", "b", "c"]""")
        val invalidData = mapper.readTree("""["a", 123, "c"]""")

        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(validData, schema)
        }
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(invalidData, schema)
        }
    }

    @Test
    fun `test format date-time should reject non-datetime strings`() {
        // Verify that format:date-time properly rejects non-datetime strings
        val schema = mapper.readTree(
            """
            {
                "type": "string",
                "format": "date-time"
            }
            """.trimIndent()
        )

        // Valid date-time should pass
        assertDoesNotThrow {
            SchemaValidator.validateJsonNode(mapper.readTree(""""2024-01-15T10:30:00Z""""), schema)
        }

        // Invalid date-time should fail
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(mapper.readTree(""""not-a-date""""), schema)
        }

        // Runtime expression should fail format:date-time validation
        assertThrows<IllegalArgumentException> {
            SchemaValidator.validateJsonNode(mapper.readTree(""""${"$"}{ contains(\"2024\") }""""), schema)
        }
    }
}
