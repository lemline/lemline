// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.core.cloudevents.CloudEventParser.parseData
import com.lemline.core.cloudevents.CloudEventParser.toJsonElement
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import io.serverlessworkflow.api.types.ListenTaskConfiguration.ListenAndReadAs
import java.net.URI
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CloudEventParserTest {

    private fun buildEvent(
        id: String = "test-id",
        source: String = "https://example.com/source",
        type: String = "com.example.test",
        subject: String? = null,
        dataContentType: String? = null,
        dataSchema: String? = null,
        time: OffsetDateTime? = null,
        data: String? = null
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(id)
            .withSource(URI.create(source))
            .withType(type)

        subject?.let { builder.withSubject(it) }
        dataContentType?.let { builder.withDataContentType(it) }
        dataSchema?.let { builder.withDataSchema(URI.create(it)) }
        time?.let { builder.withTime(it) }
        data?.let { builder.withData(dataContentType ?: "application/json", it.toByteArray()) }

        return builder.build()
    }

    @Nested
    inner class ParseDataWithJsonObject {
        @Test
        fun `should parse simple JSON object`() {
            val event = buildEvent(data = """{"key":"value"}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals("value", result.jsonObject["key"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should parse nested JSON object`() {
            val event = buildEvent(data = """{"outer":{"inner":"value"}}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals("value", result.jsonObject["outer"]?.jsonObject?.get("inner")?.jsonPrimitive?.content)
        }

        @Test
        fun `should parse JSON object with multiple fields`() {
            val event = buildEvent(data = """{"name":"John","age":30,"active":true}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals("John", result.jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals(30, result.jsonObject["age"]?.jsonPrimitive?.int)
            assertEquals(true, result.jsonObject["active"]?.jsonPrimitive?.boolean)
        }

        @Test
        fun `should parse empty JSON object`() {
            val event = buildEvent(data = """{}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertTrue(result.jsonObject.isEmpty())
        }
    }

    @Nested
    inner class ParseDataWithJsonArray {
        @Test
        fun `should parse simple JSON array`() {
            val event = buildEvent(data = """[1,2,3]""")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertEquals(3, result.jsonArray.size)
            assertEquals(1, result.jsonArray[0].jsonPrimitive.int)
        }

        @Test
        fun `should parse JSON array of objects`() {
            val event = buildEvent(data = """[{"id":1},{"id":2}]""")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertEquals(2, result.jsonArray.size)
            assertEquals(1, result.jsonArray[0].jsonObject["id"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should parse empty JSON array`() {
            val event = buildEvent(data = """[]""")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertTrue(result.jsonArray.isEmpty())
        }

        @Test
        fun `should parse nested JSON arrays`() {
            val event = buildEvent(data = """[[1,2],[3,4]]""")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertEquals(2, result.jsonArray.size)
            assertTrue(result.jsonArray[0] is JsonArray)
        }
    }

    @Nested
    inner class ParseDataWithJsonPrimitives {
        @Test
        fun `should parse JSON string primitive`() {
            val event = buildEvent(data = """"hello world"""")

            val result = event.parseData()

            assertTrue(result is JsonPrimitive)
            assertEquals("hello world", result.jsonPrimitive.content)
        }

        @Test
        fun `should parse JSON number primitive (integer)`() {
            val event = buildEvent(data = """42""")

            val result = event.parseData()

            assertTrue(result is JsonPrimitive)
            assertEquals(42, result.jsonPrimitive.int)
        }

        @Test
        fun `should parse JSON number primitive (decimal)`() {
            val event = buildEvent(data = """3.14159""")

            val result = event.parseData()

            assertTrue(result is JsonPrimitive)
            assertEquals(3.14159, result.jsonPrimitive.double, 0.00001)
        }

        @Test
        fun `should parse JSON boolean true`() {
            val event = buildEvent(data = """true""")

            val result = event.parseData()

            assertTrue(result is JsonPrimitive)
            assertEquals(true, result.jsonPrimitive.boolean)
        }

        @Test
        fun `should parse JSON boolean false`() {
            val event = buildEvent(data = """false""")

            val result = event.parseData()

            assertTrue(result is JsonPrimitive)
            assertEquals(false, result.jsonPrimitive.boolean)
        }

        @Test
        fun `should parse JSON null`() {
            val event = buildEvent(data = """null""")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }
    }

    @Nested
    inner class ParseDataWithInvalidJson {
        @Test
        fun `should return JsonNull for plain text`() {
            val event = buildEvent(data = "not valid json")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for malformed JSON object`() {
            val event = buildEvent(data = """{key: "value"}""")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for unclosed JSON object`() {
            val event = buildEvent(data = """{"key":"value"""")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for unclosed JSON array`() {
            val event = buildEvent(data = """[1,2,3""")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for trailing comma`() {
            val event = buildEvent(data = """{"key":"value",}""")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }
    }

    @Nested
    inner class ParseDataWithNullOrEmpty {
        @Test
        fun `should return JsonNull when data is null`() {
            val event = buildEvent(data = null)

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for empty string`() {
            val event = buildEvent(data = "")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return JsonNull for whitespace only`() {
            val event = buildEvent(data = "   ")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }
    }

    @Nested
    inner class ToJsonElementDataMode {
        @Test
        fun `should parse valid JSON object`() {
            val event = buildEvent(data = """{"orderId":"ORD-123"}""")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonObject)
            assertEquals("ORD-123", result.jsonObject["orderId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should parse valid JSON array`() {
            val event = buildEvent(data = """[1,2,3]""")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonArray)
            assertEquals(3, result.jsonArray.size)
        }

        @Test
        fun `should return primitive string for invalid JSON`() {
            val event = buildEvent(data = "plain text content")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("plain text content", result.jsonPrimitive.content)
        }

        @Test
        fun `should return primitive string for XML data`() {
            val event = buildEvent(
                data = "<root><item>value</item></root>",
                dataContentType = "application/xml"
            )

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("<root><item>value</item></root>", result.jsonPrimitive.content)
        }

        @Test
        fun `should return JsonNull when data is null`() {
            val event = buildEvent(data = null)

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should return primitive for empty string data`() {
            val event = buildEvent(data = "")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("", result.jsonPrimitive.content)
        }

        @Test
        fun `should return primitive for whitespace only data`() {
            val event = buildEvent(data = "   ")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("   ", result.jsonPrimitive.content)
        }

        @Test
        fun `should parse JSON string primitive`() {
            val event = buildEvent(data = """"hello"""")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("hello", result.jsonPrimitive.content)
        }

        @Test
        fun `should parse JSON number primitive`() {
            val event = buildEvent(data = "42")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals(42, result.jsonPrimitive.int)
        }

        @Test
        fun `should parse JSON boolean primitive`() {
            val event = buildEvent(data = "true")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals(true, result.jsonPrimitive.boolean)
        }

        @Test
        fun `should parse JSON null`() {
            val event = buildEvent(data = "null")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertEquals(JsonNull, result)
        }
    }

    @Nested
    inner class ToJsonElementEnvelopeMode {
        @Test
        fun `should include all required CloudEvent attributes`() {
            val event = buildEvent(
                id = "evt-001",
                source = "https://source.example.com",
                type = "com.example.event.created"
            )

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("1.0", result.jsonObject["specversion"]?.jsonPrimitive?.content)
            assertEquals("evt-001", result.jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("https://source.example.com", result.jsonObject["source"]?.jsonPrimitive?.content)
            assertEquals("com.example.event.created", result.jsonObject["type"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include optional time attribute when present`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:30:45Z")
            val event = buildEvent(time = eventTime)

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("2024-06-15T12:30:45Z", result.jsonObject["time"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include optional subject attribute when present`() {
            val event = buildEvent(subject = "/orders/12345")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("/orders/12345", result.jsonObject["subject"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include optional dataschema attribute when present`() {
            val event = buildEvent(dataSchema = "https://schemas.example.com/order.json")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals(
                "https://schemas.example.com/order.json",
                result.jsonObject["dataschema"]?.jsonPrimitive?.content
            )
        }

        @Test
        fun `should include optional datacontenttype attribute when present`() {
            val event = buildEvent(dataContentType = "application/json")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("application/json", result.jsonObject["datacontenttype"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include parsed data as JSON object`() {
            val event = buildEvent(data = """{"orderId":"ORD-123","amount":99.99}""")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonObject)
            assertEquals("ORD-123", data.jsonObject["orderId"]?.jsonPrimitive?.content)
            assertEquals(99.99, data.jsonObject["amount"]?.jsonPrimitive?.double)
        }

        @Test
        fun `should include invalid JSON data as primitive string`() {
            val event = buildEvent(data = "plain text data")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonPrimitive)
            assertEquals("plain text data", data.jsonPrimitive.content)
        }

        @Test
        fun `should not include data field when data is null`() {
            val event = buildEvent(data = null)

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertTrue(result.jsonObject["data"] == null)
        }

        @Test
        fun `should not include optional attributes when null`() {
            val event = buildEvent()

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertTrue(result.jsonObject["time"] == null)
            assertTrue(result.jsonObject["subject"] == null)
            assertTrue(result.jsonObject["dataschema"] == null)
            assertTrue(result.jsonObject["datacontenttype"] == null)
        }

        @Test
        fun `should build complete envelope with all attributes`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:30:45Z")
            val event = buildEvent(
                id = "evt-complete-001",
                source = "https://orders.example.com/api",
                type = "com.example.order.created",
                subject = "/orders/ORD-12345",
                dataContentType = "application/json",
                dataSchema = "https://schemas.example.com/order/v1.json",
                time = eventTime,
                data = """{"orderId":"ORD-12345","status":"created"}"""
            )

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("1.0", result.jsonObject["specversion"]?.jsonPrimitive?.content)
            assertEquals("evt-complete-001", result.jsonObject["id"]?.jsonPrimitive?.content)
            assertEquals("https://orders.example.com/api", result.jsonObject["source"]?.jsonPrimitive?.content)
            assertEquals("com.example.order.created", result.jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("2024-06-15T12:30:45Z", result.jsonObject["time"]?.jsonPrimitive?.content)
            assertEquals("/orders/ORD-12345", result.jsonObject["subject"]?.jsonPrimitive?.content)
            assertEquals(
                "https://schemas.example.com/order/v1.json",
                result.jsonObject["dataschema"]?.jsonPrimitive?.content
            )
            assertEquals("application/json", result.jsonObject["datacontenttype"]?.jsonPrimitive?.content)

            val data = result.jsonObject["data"]
            assertTrue(data is JsonObject)
            assertEquals("ORD-12345", data.jsonObject["orderId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should include JSON array data in envelope`() {
            val event = buildEvent(data = """[1,2,3]""")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonArray)
            assertEquals(3, data.jsonArray.size)
        }

        @Test
        fun `should include JSON number primitive data in envelope`() {
            val event = buildEvent(data = "42")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonPrimitive)
            assertEquals(42, data.jsonPrimitive.int)
        }

        @Test
        fun `should include JSON boolean data in envelope`() {
            val event = buildEvent(data = "true")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonPrimitive)
            assertEquals(true, data.jsonPrimitive.boolean)
        }

        @Test
        fun `should include JSON string primitive data in envelope`() {
            val event = buildEvent(data = """"hello world"""")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonPrimitive)
            assertEquals("hello world", data.jsonPrimitive.content)
        }

        @Test
        fun `should include empty string data as primitive in envelope`() {
            val event = buildEvent(data = "")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            val data = result.jsonObject["data"]
            assertTrue(data is JsonPrimitive)
            assertEquals("", data.jsonPrimitive.content)
        }
    }

    @Nested
    inner class ToJsonElementRawMode {
        @Test
        fun `should return data as base64-encoded string`() {
            val event = buildEvent(data = """{"orderId":"ORD-123"}""")

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("""{"orderId":"ORD-123"}""", decoded)
        }

        @Test
        fun `should return plain text as base64-encoded string`() {
            val event = buildEvent(data = "plain text content")

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("plain text content", decoded)
        }

        @Test
        fun `should return XML as base64-encoded string`() {
            val event = buildEvent(
                data = "<root><item>value</item></root>",
                dataContentType = "application/xml"
            )

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("<root><item>value</item></root>", decoded)
        }

        @Test
        fun `should return JsonNull when data is null`() {
            val event = buildEvent(data = null)

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertEquals(JsonNull, result)
        }

        @Test
        fun `should preserve JSON formatting in raw mode`() {
            val prettyJson = """{
  "key": "value",
  "nested": {
    "inner": 123
  }
}"""
            val event = buildEvent(data = prettyJson)

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals(prettyJson, decoded)
        }

        @Test
        fun `should return empty string as base64-encoded empty`() {
            val event = buildEvent(data = "")

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("", decoded)
        }

        @Test
        fun `should return whitespace as base64-encoded`() {
            val event = buildEvent(data = "   ")

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("   ", decoded)
        }

        @Test
        fun `should return newlines as base64-encoded`() {
            val event = buildEvent(data = "line1\nline2\nline3")

            val result = event.toJsonElement(ListenAndReadAs.RAW)

            assertTrue(result is JsonPrimitive)
            val decoded = String(java.util.Base64.getDecoder().decode(result.jsonPrimitive.content))
            assertEquals("line1\nline2\nline3", decoded)
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle unicode characters in data`() {
            val event = buildEvent(data = """{"message":"Hello, \u4e16\u754c! \uD83D\uDE00"}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            val message = result.jsonObject["message"]?.jsonPrimitive?.content
            assertTrue(message?.contains("\u4e16\u754c") == true)
        }

        @Test
        fun `should handle escaped characters in JSON`() {
            val event = buildEvent(data = """{"path":"C:\\Users\\test","quote":"He said \"hello\""}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals("C:\\Users\\test", result.jsonObject["path"]?.jsonPrimitive?.content)
            assertEquals("He said \"hello\"", result.jsonObject["quote"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle large JSON data`() {
            val largeArray = (1..1000).joinToString(",") { """{"id":$it,"value":"item-$it"}""" }
            val event = buildEvent(data = "[$largeArray]")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertEquals(1000, result.jsonArray.size)
        }

        @Test
        fun `should handle deeply nested JSON`() {
            val deeplyNested = """{"l1":{"l2":{"l3":{"l4":{"l5":{"value":"deep"}}}}}}"""
            val event = buildEvent(data = deeplyNested)

            val result = event.parseData()

            assertTrue(result is JsonObject)
            val value = result.jsonObject["l1"]
                ?.jsonObject?.get("l2")
                ?.jsonObject?.get("l3")
                ?.jsonObject?.get("l4")
                ?.jsonObject?.get("l5")
                ?.jsonObject?.get("value")
                ?.jsonPrimitive?.content
            assertEquals("deep", value)
        }

        @Test
        fun `should handle JSON with special number formats`() {
            val event = buildEvent(data = """{"sci":1.23e10,"neg":-456,"zero":0}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals(1.23e10, result.jsonObject["sci"]?.jsonPrimitive?.double)
            assertEquals(-456, result.jsonObject["neg"]?.jsonPrimitive?.int)
            assertEquals(0, result.jsonObject["zero"]?.jsonPrimitive?.int)
        }

        @Test
        fun `should handle newlines and whitespace in JSON`() {
            val event = buildEvent(
                data = """
                {
                    "key": "value",
                    "number": 123
                }
                """.trimIndent()
            )

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals("value", result.jsonObject["key"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle URN style source in envelope`() {
            val event = buildEvent(source = "urn:lemline:workflow:order-processor")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("urn:lemline:workflow:order-processor", result.jsonObject["source"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle relative URI source in envelope`() {
            val event = buildEvent(source = "/workflows/my-workflow")

            val result = event.toJsonElement(ListenAndReadAs.ENVELOPE)

            assertTrue(result is JsonObject)
            assertEquals("/workflows/my-workflow", result.jsonObject["source"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle empty JSON object in data mode`() {
            val event = buildEvent(data = "{}")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonObject)
            assertTrue(result.jsonObject.isEmpty())
        }

        @Test
        fun `should handle empty JSON array in data mode`() {
            val event = buildEvent(data = "[]")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonArray)
            assertTrue(result.jsonArray.isEmpty())
        }

        @Test
        fun `should handle JSON with null values`() {
            val event = buildEvent(data = """{"key":null,"other":"value"}""")

            val result = event.parseData()

            assertTrue(result is JsonObject)
            assertEquals(JsonNull, result.jsonObject["key"])
            assertEquals("value", result.jsonObject["other"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should handle mixed array types`() {
            val event = buildEvent(data = """[1,"two",true,null,{"key":"value"},[1,2]]""")

            val result = event.parseData()

            assertTrue(result is JsonArray)
            assertEquals(6, result.jsonArray.size)
            assertEquals(1, result.jsonArray[0].jsonPrimitive.int)
            assertEquals("two", result.jsonArray[1].jsonPrimitive.content)
            assertEquals(true, result.jsonArray[2].jsonPrimitive.boolean)
            assertEquals(JsonNull, result.jsonArray[3])
            assertTrue(result.jsonArray[4] is JsonObject)
            assertTrue(result.jsonArray[5] is JsonArray)
        }
    }

    @Nested
    inner class DifferencesBetweenParseDataAndDataMode {
        @Test
        fun `parseData returns JsonNull for invalid JSON`() {
            val event = buildEvent(data = "not valid json")

            val result = event.parseData()

            assertEquals(JsonNull, result)
        }

        @Test
        fun `DATA mode returns primitive string for invalid JSON`() {
            val event = buildEvent(data = "not valid json")

            val result = event.toJsonElement(ListenAndReadAs.DATA)

            assertTrue(result is JsonPrimitive)
            assertEquals("not valid json", result.jsonPrimitive.content)
        }

        @Test
        fun `both return same result for valid JSON`() {
            val event = buildEvent(data = """{"key":"value"}""")

            val parseDataResult = event.parseData()
            val dataModeResult = event.toJsonElement(ListenAndReadAs.DATA)

            assertEquals(parseDataResult, dataModeResult)
        }

        @Test
        fun `both return JsonNull for null data`() {
            val event = buildEvent(data = null)

            val parseDataResult = event.parseData()
            val dataModeResult = event.toJsonElement(ListenAndReadAs.DATA)

            assertEquals(JsonNull, parseDataResult)
            assertEquals(JsonNull, dataModeResult)
        }
    }
}
