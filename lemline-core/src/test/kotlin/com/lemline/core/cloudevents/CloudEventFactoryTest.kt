// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.core.processors.EmitConfig
import java.net.URI
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CloudEventFactoryTest {

    @Nested
    inner class RequiredFields {
        @Test
        fun `should build CloudEvent with only required fields`() {
            // Given
            val config = EmitConfig(
                id = "test-id-123",
                source = "https://example.com/source",
                type = "com.example.test.event"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("test-id-123", event.id)
            assertEquals(URI.create("https://example.com/source"), event.source)
            assertEquals("com.example.test.event", event.type)
            assertEquals("1.0", event.specVersion.toString())
        }

        @Test
        fun `should handle source with path components`() {
            // Given
            val config = EmitConfig(
                id = "event-1",
                source = "https://api.example.com/v1/workflows/orders",
                type = "order.created"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("https://api.example.com/v1/workflows/orders"), event.source)
        }

        @Test
        fun `should handle URN style source`() {
            // Given
            val config = EmitConfig(
                id = "event-1",
                source = "urn:lemline:workflow:order-processor",
                type = "order.processed"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("urn:lemline:workflow:order-processor"), event.source)
        }

        @Test
        fun `should handle relative URI source`() {
            // Given
            val config = EmitConfig(
                id = "event-1",
                source = "/workflows/my-workflow",
                type = "task.completed"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("/workflows/my-workflow"), event.source)
        }
    }

    @Nested
    inner class TimeHandling {
        @Test
        fun `should parse ISO 8601 timestamp with timezone`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = "2024-06-15T14:30:00+02:00"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.time)
            assertEquals(OffsetDateTime.parse("2024-06-15T14:30:00+02:00"), event.time)
        }

        @Test
        fun `should parse UTC timestamp`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = "2024-06-15T12:30:00Z"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.time)
            assertEquals(OffsetDateTime.parse("2024-06-15T12:30:00Z"), event.time)
        }

        @Test
        fun `should parse timestamp with milliseconds`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = "2024-06-15T12:30:00.123Z"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.time)
            assertEquals(OffsetDateTime.parse("2024-06-15T12:30:00.123Z"), event.time)
        }

        @Test
        fun `should not set time when null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNull(event.time)
        }

        @Test
        fun `should throw exception for invalid timestamp format`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = "invalid-timestamp"
            )

            // When & Then
            assertThrows<java.time.format.DateTimeParseException> {
                CloudEventFactory.build(config)
            }
        }

        @Test
        fun `should throw exception for date without time`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                time = "2024-06-15"
            )

            // When & Then
            assertThrows<java.time.format.DateTimeParseException> {
                CloudEventFactory.build(config)
            }
        }
    }

    @Nested
    inner class SubjectHandling {
        @Test
        fun `should set subject when provided`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                subject = "order-12345"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("order-12345", event.subject)
        }

        @Test
        fun `should not set subject when null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                subject = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNull(event.subject)
        }

        @Test
        fun `should handle subject with special characters`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                subject = "/users/john.doe@example.com/orders/123"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("/users/john.doe@example.com/orders/123", event.subject)
        }

        @Test
        fun `should handle empty subject string`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                subject = ""
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("", event.subject)
        }
    }

    @Nested
    inner class DataSchemaHandling {
        @Test
        fun `should set dataschema as URI`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                dataschema = "https://schemas.example.com/order/v1.json"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("https://schemas.example.com/order/v1.json"), event.dataSchema)
        }

        @Test
        fun `should not set dataschema when null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                dataschema = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNull(event.dataSchema)
        }

        @Test
        fun `should handle URN style dataschema`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                dataschema = "urn:jsonschema:com:example:Order"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("urn:jsonschema:com:example:Order"), event.dataSchema)
        }
    }

    @Nested
    inner class DataContentTypeHandling {
        @Test
        fun `should use default content type when data present but no contenttype specified`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonPrimitive("test data")
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("application/json", event.dataContentType)
        }

        @Test
        fun `should use specified content type when data present`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                datacontenttype = "text/plain",
                data = JsonPrimitive("plain text data")
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("text/plain", event.dataContentType)
        }

        @Test
        fun `should set content type without data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                datacontenttype = "application/xml"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("application/xml", event.dataContentType)
        }

        @Test
        fun `should not set content type when both null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                datacontenttype = null,
                data = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNull(event.dataContentType)
        }

        @Test
        fun `should handle application+json subtype`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                datacontenttype = "application/cloudevents+json"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("application/cloudevents+json", event.dataContentType)
        }
    }

    @Nested
    inner class DataHandling {
        @Test
        fun `should serialize JsonObject data`() {
            // Given
            val data = buildJsonObject {
                put("orderId", "12345")
                put("amount", 99.99)
                put("items", 3)
            }
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "order.created",
                data = data
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataBytes = event.data!!.toBytes()
            val dataString = String(dataBytes)
            assertTrue(dataString.contains("\"orderId\":\"12345\""))
            assertTrue(dataString.contains("\"amount\":99.99"))
        }

        @Test
        fun `should serialize JsonPrimitive string data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonPrimitive("simple string value")
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertEquals("\"simple string value\"", dataString)
        }

        @Test
        fun `should serialize JsonPrimitive number data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonPrimitive(42)
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertEquals("42", dataString)
        }

        @Test
        fun `should serialize JsonPrimitive boolean data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonPrimitive(true)
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertEquals("true", dataString)
        }

        @Test
        fun `should serialize JsonNull data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonNull
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertEquals("null", dataString)
        }

        @Test
        fun `should handle nested JsonObject data`() {
            // Given
            val data = buildJsonObject {
                put("order", buildJsonObject {
                    put("id", "ord-123")
                    put("customer", buildJsonObject {
                        put("name", "John Doe")
                        put("email", "john@example.com")
                    })
                })
            }
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "order.created",
                data = data
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertTrue(dataString.contains("\"name\":\"John Doe\""))
            assertTrue(dataString.contains("\"email\":\"john@example.com\""))
        }

        @Test
        fun `should not set data when null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNull(event.data)
        }

        @Test
        fun `should handle empty JsonObject data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = JsonObject(emptyMap())
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertEquals("{}", dataString)
        }
    }

    @Nested
    inner class ExtensionsHandling {
        @Test
        fun `should add single extension`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = mapOf("traceparent" to "00-abc123-def456-01")
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("00-abc123-def456-01", event.getExtension("traceparent"))
        }

        @Test
        fun `should add multiple extensions`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = mapOf(
                    "traceparent" to "00-abc123-def456-01",
                    "tracestate" to "vendor=value",
                    "customext" to "custom-value"
                )
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("00-abc123-def456-01", event.getExtension("traceparent"))
            assertEquals("vendor=value", event.getExtension("tracestate"))
            assertEquals("custom-value", event.getExtension("customext"))
        }

        @Test
        fun `should not add extensions when null`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = null
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertTrue(event.extensionNames.isEmpty())
        }

        @Test
        fun `should handle empty extensions map`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = emptyMap()
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertTrue(event.extensionNames.isEmpty())
        }

        @Test
        fun `should handle extension with empty string value`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = mapOf("emptyext" to "")
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("", event.getExtension("emptyext"))
        }

        @Test
        fun `should handle common CloudEvents extensions`() {
            // Given - using standard CloudEvents extensions
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                extensions = mapOf(
                    "partitionkey" to "user-123",
                    "sequencetype" to "Integer",
                    "sequence" to "5"
                )
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("user-123", event.getExtension("partitionkey"))
            assertEquals("Integer", event.getExtension("sequencetype"))
            assertEquals("5", event.getExtension("sequence"))
        }
    }

    @Nested
    inner class FullEventConstruction {
        @Test
        fun `should build complete CloudEvent with all fields`() {
            // Given
            val data = buildJsonObject {
                put("orderId", "ord-789")
                put("status", "confirmed")
            }
            val config = EmitConfig(
                id = "evt-2024-06-15-001",
                source = "https://orders.example.com/api/v1",
                type = "com.example.order.confirmed",
                time = "2024-06-15T10:30:00Z",
                subject = "/orders/ord-789",
                dataschema = "https://schemas.example.com/order-confirmed/v1.json",
                datacontenttype = "application/json",
                data = data,
                extensions = mapOf(
                    "correlationid" to "corr-abc-123",
                    "traceparent" to "00-trace-id-span-01"
                )
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("evt-2024-06-15-001", event.id)
            assertEquals(URI.create("https://orders.example.com/api/v1"), event.source)
            assertEquals("com.example.order.confirmed", event.type)
            assertEquals(OffsetDateTime.parse("2024-06-15T10:30:00Z"), event.time)
            assertEquals("/orders/ord-789", event.subject)
            assertEquals(URI.create("https://schemas.example.com/order-confirmed/v1.json"), event.dataSchema)
            assertEquals("application/json", event.dataContentType)
            assertNotNull(event.data)
            assertEquals("corr-abc-123", event.getExtension("correlationid"))
            assertEquals("00-trace-id-span-01", event.getExtension("traceparent"))
            assertEquals("1.0", event.specVersion.toString())
        }

        @Test
        fun `should build event suitable for workflow lifecycle`() {
            // Given - simulating a workflow lifecycle event
            val config = EmitConfig(
                id = "wf-evt-001",
                source = "urn:lemline:workflow:order-processor/v1.0.0",
                type = "io.lemline.workflow.started",
                time = "2024-06-15T12:00:00Z",
                subject = "instance-abc123",
                data = buildJsonObject {
                    put("workflowName", "order-processor")
                    put("instanceId", "instance-abc123")
                    put("input", buildJsonObject {
                        put("orderId", "12345")
                    })
                },
                extensions = mapOf(
                    "workflowid" to "order-processor",
                    "instanceid" to "instance-abc123"
                )
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("wf-evt-001", event.id)
            assertEquals("io.lemline.workflow.started", event.type)
            assertEquals("instance-abc123", event.subject)
            assertEquals("order-processor", event.getExtension("workflowid"))
            assertEquals("instance-abc123", event.getExtension("instanceid"))
        }

        @Test
        fun `should build event suitable for task lifecycle`() {
            // Given - simulating a task lifecycle event
            val config = EmitConfig(
                id = "task-evt-001",
                source = "urn:lemline:workflow:order-processor/v1.0.0/instance-abc123",
                type = "io.lemline.task.completed",
                time = "2024-06-15T12:05:00Z",
                subject = "/do/0/validateOrder",
                data = buildJsonObject {
                    put("taskName", "validateOrder")
                    put("duration", 150)
                    put("output", buildJsonObject {
                        put("valid", true)
                    })
                },
                extensions = mapOf(
                    "taskposition" to "/do/0/validateOrder",
                    "taskname" to "validateOrder"
                )
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("task-evt-001", event.id)
            assertEquals("io.lemline.task.completed", event.type)
            assertEquals("/do/0/validateOrder", event.subject)
            assertEquals("/do/0/validateOrder", event.getExtension("taskposition"))
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle very long id`() {
            // Given
            val longId = "a".repeat(1000)
            val config = EmitConfig(
                id = longId,
                source = "https://example.com",
                type = "test.event"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(longId, event.id)
        }

        @Test
        fun `should handle unicode characters in data`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                data = buildJsonObject {
                    put("message", "Hello, World! Korean: \uD55C\uAD6D\uC5B4 Japanese: \u65E5\u672C\u8A9E Emoji: \uD83D\uDE00")
                }
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertNotNull(event.data)
            val dataString = String(event.data!!.toBytes())
            assertTrue(dataString.contains("\uD55C\uAD6D\uC5B4"))
            assertTrue(dataString.contains("\u65E5\u672C\u8A9E"))
        }

        @Test
        fun `should handle special characters in type`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "com.example.my-service.order_created.v1"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals("com.example.my-service.order_created.v1", event.type)
        }

        @Test
        fun `should handle source with query parameters`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com/api?version=1&env=prod",
                type = "test.event"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("https://example.com/api?version=1&env=prod"), event.source)
        }

        @Test
        fun `should handle source with fragment`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com/api#section",
                type = "test.event"
            )

            // When
            val event = CloudEventFactory.build(config)

            // Then
            assertEquals(URI.create("https://example.com/api#section"), event.source)
        }

        @Test
        fun `should throw exception for invalid source URI`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "not a valid uri with spaces",
                type = "test.event"
            )

            // When & Then
            assertThrows<IllegalArgumentException> {
                CloudEventFactory.build(config)
            }
        }

        @Test
        fun `should throw exception for invalid dataschema URI`() {
            // Given
            val config = EmitConfig(
                id = "test-id",
                source = "https://example.com",
                type = "test.event",
                dataschema = "not a valid uri with spaces"
            )

            // When & Then
            assertThrows<IllegalArgumentException> {
                CloudEventFactory.build(config)
            }
        }
    }
}
