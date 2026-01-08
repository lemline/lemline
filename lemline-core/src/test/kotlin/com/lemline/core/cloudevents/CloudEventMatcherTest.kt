// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.cloudevents

import com.lemline.core.processors.CorrelationDef
import com.lemline.core.processors.EventFilter
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class CloudEventMatcherTest {

    private fun buildEvent(
        id: String = "test-id",
        source: String = "https://example.com/source",
        type: String = "com.example.test",
        subject: String? = null,
        dataContentType: String? = null,
        dataSchema: String? = null,
        time: OffsetDateTime? = null,
        data: ByteArray? = null
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(id)
            .withSource(URI.create(source))
            .withType(type)

        subject?.let { builder.withSubject(it) }
        dataContentType?.let { builder.withDataContentType(it) }
        dataSchema?.let { builder.withDataSchema(URI.create(it)) }
        time?.let { builder.withTime(it) }
        data?.let { builder.withData(dataContentType ?: "application/json", it) }

        return builder.build()
    }

    @Nested
    inner class MatchesLiteralField {
        @Test
        fun `should return true when filter is null`() {
            assertTrue(CloudEventMatcher.matchesLiteralField(null, "any-value"))
        }

        @Test
        fun `should return true when filter is null and event value is null`() {
            assertTrue(CloudEventMatcher.matchesLiteralField(null, null))
        }

        @Test
        fun `should return true when both values match`() {
            assertTrue(CloudEventMatcher.matchesLiteralField("expected", "expected"))
        }

        @Test
        fun `should return false when values do not match`() {
            assertFalse(CloudEventMatcher.matchesLiteralField("expected", "actual"))
        }

        @Test
        fun `should return false when filter is set but event value is null`() {
            assertFalse(CloudEventMatcher.matchesLiteralField("expected", null))
        }

        @Test
        fun `should be case sensitive`() {
            assertFalse(CloudEventMatcher.matchesLiteralField("Expected", "expected"))
        }

        @Test
        fun `should match empty string exactly`() {
            assertTrue(CloudEventMatcher.matchesLiteralField("", ""))
            assertFalse(CloudEventMatcher.matchesLiteralField("", "non-empty"))
        }
    }

    @Nested
    inner class MatchesExprField {
        @Test
        fun `should return true when filter is null`() {
            assertTrue(CloudEventMatcher.matchesExprField(null, "any-value"))
        }

        @Test
        fun `should return false when filter is set but event value is null`() {
            assertFalse(CloudEventMatcher.matchesExprField("expected", null))
        }

        @Test
        fun `should match literal string exactly when not an expression`() {
            assertTrue(CloudEventMatcher.matchesExprField("https://example.com", "https://example.com"))
            assertFalse(CloudEventMatcher.matchesExprField("https://example.com", "https://other.com"))
        }

        @Test
        fun `should evaluate contains expression`() {
            assertTrue(
                CloudEventMatcher.matchesExprField(
                    $$"${contains(\"orders\")}",
                    "https://orders.example.com/api"
                )
            )
            assertFalse(
                CloudEventMatcher.matchesExprField(
                    $$"${contains(\"users\")}",
                    "https://orders.example.com/api"
                )
            )
        }

        @Test
        fun `should evaluate startswith expression`() {
            assertTrue(CloudEventMatcher.matchesExprField($$"${startswith(\"https://\")}", "https://example.com"))
            assertFalse(CloudEventMatcher.matchesExprField($$"${startswith(\"http://\")}", "https://example.com"))
        }

        @Test
        fun `should evaluate endswith expression`() {
            assertTrue(CloudEventMatcher.matchesExprField($$"${endswith(\"/api\")}", "https://example.com/api"))
            assertFalse(CloudEventMatcher.matchesExprField($$"${endswith(\"/v1\")}", "https://example.com/api"))
        }

        @Test
        fun `should evaluate equality expression`() {
            assertTrue(CloudEventMatcher.matchesExprField($$"${. == \"exact-match\"}", "exact-match"))
            assertFalse(CloudEventMatcher.matchesExprField($$"${. == \"exact-match\"}", "different"))
        }

        @Test
        fun `should handle malformed expressions gracefully`() {
            assertFalse(CloudEventMatcher.matchesExprField($$"${.[[[invalid}", "value"))
        }
    }

    @Nested
    inner class MatchesTimeField {
        @Test
        fun `should return true when filter is null`() {
            assertTrue(CloudEventMatcher.matchesTimeField(null, OffsetDateTime.now()))
        }

        @Test
        fun `should return false when filter is set but event time is null`() {
            assertFalse(CloudEventMatcher.matchesTimeField("2024-06-15T12:00:00Z", null))
        }

        @Test
        fun `should match exact timestamp`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            assertTrue(CloudEventMatcher.matchesTimeField("2024-06-15T12:00:00Z", eventTime))
        }

        @Test
        fun `should match timestamp with different timezone representations`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T14:00:00+02:00")
            // Same instant as 2024-06-15T12:00:00Z
            assertTrue(CloudEventMatcher.matchesTimeField("2024-06-15T12:00:00Z", eventTime))
        }

        @Test
        fun `should evaluate greater than expression`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            assertTrue(CloudEventMatcher.matchesTimeField($$"${. > \"2024-01-01T00:00:00Z\"}", eventTime))
            assertFalse(CloudEventMatcher.matchesTimeField($$"${. > \"2024-12-31T00:00:00Z\"}", eventTime))
        }

        @Test
        fun `should evaluate less than expression`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            assertTrue(CloudEventMatcher.matchesTimeField($$"${. < \"2024-12-31T00:00:00Z\"}", eventTime))
            assertFalse(CloudEventMatcher.matchesTimeField($$"${. < \"2024-01-01T00:00:00Z\"}", eventTime))
        }

        @Test
        fun `should handle malformed time expressions gracefully`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            assertFalse(CloudEventMatcher.matchesTimeField($$"${.[[[invalid}", eventTime))
        }
    }

    @Nested
    inner class EvaluateExpressionAsBoolean {
        @Test
        fun `should return true for true expression`() {
            val input = JsonPrimitive("test")
            assertTrue(CloudEventMatcher.evaluateExpressionAsBoolean($$"${true}", input))
        }

        @Test
        fun `should return false for false expression`() {
            val input = JsonPrimitive("test")
            assertFalse(CloudEventMatcher.evaluateExpressionAsBoolean($$"${false}", input))
        }

        @Test
        fun `should evaluate comparison expressions`() {
            val input = JsonPrimitive(42)
            assertTrue(CloudEventMatcher.evaluateExpressionAsBoolean($$"${. > 10}", input))
            assertFalse(CloudEventMatcher.evaluateExpressionAsBoolean($$"${. > 100}", input))
        }

        @Test
        fun `should return false for non-boolean results`() {
            val input = JsonPrimitive("test")
            // Expression returns string, not boolean
            assertFalse(CloudEventMatcher.evaluateExpressionAsBoolean($$"${.}", input))
        }

        @Test
        fun `should return false for malformed expressions`() {
            val input = JsonPrimitive("test")
            assertFalse(CloudEventMatcher.evaluateExpressionAsBoolean($$"${.[[[invalid}", input))
        }
    }

    @Nested
    inner class EvaluateFromExpression {
        @Test
        fun `should extract simple field value`() {
            val eventData = buildJsonObject {
                put("orderId", "order-123")
            }
            val result = CloudEventMatcher.evaluateFromExpression(".orderId", eventData)
            assertEquals("order-123", result)
        }

        @Test
        fun `should extract nested field value`() {
            val eventData = buildJsonObject {
                put("customer", buildJsonObject {
                    put("id", "cust-456")
                })
            }
            val result = CloudEventMatcher.evaluateFromExpression(".customer.id", eventData)
            assertEquals("cust-456", result)
        }

        @Test
        fun `should handle expression syntax with dollar-brace`() {
            val eventData = buildJsonObject {
                put("key", "value")
            }
            val result = CloudEventMatcher.evaluateFromExpression($$"${.key}", eventData)
            assertEquals("value", result)
        }

        @Test
        fun `should return null for missing field`() {
            val eventData = buildJsonObject {
                put("existing", "value")
            }
            val result = CloudEventMatcher.evaluateFromExpression(".missing", eventData)
            assertNull(result)
        }

        @Test
        fun `should return null for invalid expression`() {
            val eventData = buildJsonObject {
                put("key", "value")
            }
            val result = CloudEventMatcher.evaluateFromExpression(".[[[invalid", eventData)
            assertNull(result)
        }

        @Test
        fun `should extract numeric value as string`() {
            val eventData = buildJsonObject {
                put("count", 42)
            }
            val result = CloudEventMatcher.evaluateFromExpression(".count", eventData)
            assertEquals("42", result)
        }

        @Test
        fun `should extract boolean value as string`() {
            val eventData = buildJsonObject {
                put("active", true)
            }
            val result = CloudEventMatcher.evaluateFromExpression(".active", eventData)
            assertEquals("true", result)
        }

        @Test
        fun `should serialize object result to JSON string`() {
            val eventData = buildJsonObject {
                put("nested", buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                })
            }
            val result = CloudEventMatcher.evaluateFromExpression(".nested", eventData)
            assertNotNull(result)
            assertTrue(result.contains("\"a\":1"))
            assertTrue(result.contains("\"b\":2"))
        }

        @Test
        fun `should serialize array result to JSON string`() {
            val eventData = buildJsonObject {
                put("items", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("a"))
                    add(JsonPrimitive("b"))
                })
            }
            val result = CloudEventMatcher.evaluateFromExpression(".items", eventData)
            assertNotNull(result)
            assertEquals("[\"a\",\"b\"]", result)
        }
    }

    @Nested
    inner class ExtractCorrelationValues {
        @Test
        fun `should return null when no correlations defined`() {
            val filter = EventFilter(type = "test.event")
            val eventData = buildJsonObject { put("key", "value") }
            assertNull(CloudEventMatcher.extractCorrelationValues(filter, eventData))
        }

        @Test
        fun `should return null when correlations is empty`() {
            val filter = EventFilter(type = "test.event", correlations = emptyMap())
            val eventData = buildJsonObject { put("key", "value") }
            assertNull(CloudEventMatcher.extractCorrelationValues(filter, eventData))
        }

        @Test
        fun `should extract single correlation value`() {
            val filter = EventFilter(
                type = "test.event",
                correlations = mapOf("orderId" to CorrelationDef(from = ".orderId"))
            )
            val eventData = buildJsonObject { put("orderId", "order-123") }

            val result = CloudEventMatcher.extractCorrelationValues(filter, eventData)

            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("order-123", result["orderId"])
        }

        @Test
        fun `should extract multiple correlation values`() {
            val filter = EventFilter(
                type = "test.event",
                correlations = mapOf(
                    "orderId" to CorrelationDef(from = ".orderId"),
                    "customerId" to CorrelationDef(from = ".customer.id")
                )
            )
            val eventData = buildJsonObject {
                put("orderId", "order-123")
                put("customer", buildJsonObject { put("id", "cust-456") })
            }

            val result = CloudEventMatcher.extractCorrelationValues(filter, eventData)

            assertNotNull(result)
            assertEquals(2, result.size)
            assertEquals("order-123", result["orderId"])
            assertEquals("cust-456", result["customerId"])
        }

        @Test
        fun `should skip correlation values that cannot be extracted`() {
            val filter = EventFilter(
                type = "test.event",
                correlations = mapOf(
                    "existing" to CorrelationDef(from = ".existing"),
                    "missing" to CorrelationDef(from = ".missing")
                )
            )
            val eventData = buildJsonObject { put("existing", "value") }

            val result = CloudEventMatcher.extractCorrelationValues(filter, eventData)

            assertNotNull(result)
            assertEquals(1, result.size)
            assertEquals("value", result["existing"])
        }

        @Test
        fun `should return null when no values can be extracted`() {
            val filter = EventFilter(
                type = "test.event",
                correlations = mapOf("missing" to CorrelationDef(from = ".missing"))
            )
            val eventData = buildJsonObject { put("other", "value") }

            assertNull(CloudEventMatcher.extractCorrelationValues(filter, eventData))
        }
    }

    @Nested
    inner class MatchesFiltersBasic {
        @Test
        fun `should return true for empty filter list`() {
            val event = buildEvent()
            assertTrue(CloudEventMatcher.matchesFilters(event, emptyList()))
        }

        @Test
        fun `should match on type field`() {
            val event = buildEvent(type = "com.example.order.created")

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(type = "com.example.order.created"))))
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(type = "com.example.order.deleted"))
                )
            )
        }

        @Test
        fun `should match on id field`() {
            val event = buildEvent(id = "event-123")

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(id = "event-123"))))
            assertFalse(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(id = "event-456"))))
        }

        @Test
        fun `should match on subject field`() {
            val event = buildEvent(subject = "/orders/12345")

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(subject = "/orders/12345"))))
            assertFalse(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(subject = "/orders/67890"))))
        }

        @Test
        fun `should match on datacontenttype field`() {
            val event = buildEvent(dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(datacontenttype = "application/json"))
                )
            )
            assertFalse(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(datacontenttype = "text/plain"))))
        }

        @Test
        fun `should match on source with literal value`() {
            val event = buildEvent(source = "https://orders.example.com")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = "https://orders.example.com"))
                )
            )
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = "https://other.example.com"))
                )
            )
        }

        @Test
        fun `should match on source with expression`() {
            val event = buildEvent(source = "https://orders.example.com/api/v1")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = $$"${contains(\"orders\")}"))
                )
            )
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = $$"${contains(\"users\")}"))
                )
            )
        }

        @Test
        fun `should match on dataschema with literal value`() {
            val event = buildEvent(dataSchema = "https://schemas.example.com/order.json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataschema = "https://schemas.example.com/order.json"))
                )
            )
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataschema = "https://schemas.example.com/other.json"))
                )
            )
        }

        @Test
        fun `should match on dataschema with expression`() {
            val event = buildEvent(dataSchema = "https://schemas.example.com/order/v2.json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataschema = $$"${endswith(\"v2.json\")}"))
                )
            )
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataschema = $$"${endswith(\"v1.json\")}"))
                )
            )
        }

        @Test
        fun `should match on time with literal value`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            val event = buildEvent(time = eventTime)

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(time = "2024-06-15T12:00:00Z"))))
        }

        @Test
        fun `should match on time with expression`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            val event = buildEvent(time = eventTime)

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(time = $$"${. > \"2024-01-01T00:00:00Z\"}"))
                )
            )
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(time = $$"${. > \"2024-12-31T00:00:00Z\"}"))
                )
            )
        }
    }

    @Nested
    inner class MatchesFiltersMultipleFields {
        @Test
        fun `should require all fields to match`() {
            val event = buildEvent(
                type = "com.example.order.created",
                source = "https://orders.example.com"
            )

            // Both fields match
            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(
                        EventFilter(
                            type = "com.example.order.created",
                            source = "https://orders.example.com"
                        )
                    )
                )
            )

            // Only type matches
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(
                        EventFilter(
                            type = "com.example.order.created",
                            source = "https://other.example.com"
                        )
                    )
                )
            )

            // Only source matches
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(
                        EventFilter(
                            type = "com.example.other.type",
                            source = "https://orders.example.com"
                        )
                    )
                )
            )
        }

        @Test
        fun `should match if any filter in list matches`() {
            val event = buildEvent(type = "com.example.order.created")

            // Second filter matches
            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(
                        EventFilter(type = "com.example.order.deleted"),
                        EventFilter(type = "com.example.order.created")
                    )
                )
            )

            // No filter matches
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(
                        EventFilter(type = "com.example.order.deleted"),
                        EventFilter(type = "com.example.order.updated")
                    )
                )
            )
        }
    }

    @Nested
    inner class DataFilterMatching {
        @Test
        fun `should match JSON literal data filter`() {
            val eventData = """{"status":"completed","count":5}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = """{"status":"completed","count":5}"""))
                )
            )
        }

        @Test
        fun `should not match different JSON literal data filter`() {
            val eventData = """{"status":"completed","count":5}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = """{"status":"pending","count":5}"""))
                )
            )
        }

        @Test
        fun `should evaluate JQ expression data filter`() {
            val eventData = """{"status":"completed","count":5}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = """.status == "completed""""))
                )
            )

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = """.status == "pending""""))
                )
            )
        }

        @Test
        fun `should evaluate numeric comparison in data filter`() {
            val eventData = """{"temperature":38.5}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = ".temperature > 37"))
                )
            )

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = ".temperature > 40"))
                )
            )
        }

        @Test
        fun `should return false for null event data with data filter`() {
            val event = buildEvent()

            // Event has no data, but filter expects data
            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = ".status == \"completed\""))
                ) { JsonNull }
            )
        }

        @Test
        fun `should match without data filter when filter is null`() {
            val eventData = """{"status":"completed"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(type = event.type))
                )
            )
        }

        @Test
        fun `should match JSON array literal data filter`() {
            val eventData = """[1,2,3]"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = "[1,2,3]"))
                )
            )

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = "[1,2,4]"))
                )
            )
        }

        @Test
        fun `should return false for malformed JSON literal data filter`() {
            val eventData = """{"status":"completed"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = "{invalid json"))
                )
            )
        }

        @Test
        fun `should handle whitespace in JSON literal data filter`() {
            val eventData = """{"key":"value"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(dataFilter = """  {"key":"value"}  """))
                )
            )
        }
    }

    @Nested
    inner class CorrelationMatching {
        @Test
        fun `should match without correlations when not defined`() {
            val event = buildEvent()
            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(type = event.type)),
                    correlationContext = null,
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should pass when correlations defined but no context provided`() {
            val eventData = """{"orderId":"order-123"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf("orderId" to CorrelationDef(from = ".orderId"))
            )

            // No correlationContext or establishedCorrelations - should pass (no correlation to check)
            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should establish correlation on first event`() {
            val eventData = """{"orderId":"order-123"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf("orderId" to CorrelationDef(from = ".orderId"))
            )
            val established = mutableMapOf<String, String>()

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )

            assertEquals("order-123", established["orderId"])
        }

        @Test
        fun `should match auto-correlation with established value`() {
            val eventData = """{"orderId":"order-123"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf("orderId" to CorrelationDef(from = ".orderId"))
            )
            val established = mutableMapOf("orderId" to "order-123")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }

        @Test
        fun `should not match auto-correlation with different established value`() {
            val eventData = """{"orderId":"order-456"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf("orderId" to CorrelationDef(from = ".orderId"))
            )
            val established = mutableMapOf("orderId" to "order-123")

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }

        @Test
        fun `should match expect-based correlation with context`() {
            val eventData = """{"customerId":"cust-789"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "customerId" to CorrelationDef(
                        from = ".customerId",
                        expect = $$"${$customer.id}"
                    )
                )
            )
            val context = buildJsonObject {
                put("customer", buildJsonObject { put("id", "cust-789") })
            }

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = context,
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should not match expect-based correlation with mismatched value`() {
            val eventData = """{"customerId":"cust-789"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "customerId" to CorrelationDef(
                        from = ".customerId",
                        expect = $$"${$customer.id}"
                    )
                )
            )
            val context = buildJsonObject {
                put("customer", buildJsonObject { put("id", "cust-different") })
            }

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = context,
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should not match expect-based correlation without context`() {
            val eventData = """{"customerId":"cust-789"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "customerId" to CorrelationDef(
                        from = ".customerId",
                        expect = $$"${$customer.id}"
                    )
                )
            )

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = mutableMapOf()
                )
            )
        }

        @Test
        fun `should match expect with literal value`() {
            val eventData = """{"status":"approved"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "status" to CorrelationDef(
                        from = ".status",
                        expect = "approved"  // Literal value, not expression
                    )
                )
            )

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = buildJsonObject { },  // Empty context
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should not match when from expression fails to extract value`() {
            val eventData = """{"otherField":"value"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "orderId" to CorrelationDef(from = ".orderId")  // Field doesn't exist
                )
            )
            val established = mutableMapOf<String, String>()

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }

        @Test
        fun `should match multiple correlations`() {
            val eventData = """{"orderId":"order-123","customerId":"cust-456"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "orderId" to CorrelationDef(from = ".orderId"),
                    "customerId" to CorrelationDef(from = ".customerId")
                )
            )
            val established = mutableMapOf("orderId" to "order-123", "customerId" to "cust-456")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }

        @Test
        fun `should not match when any correlation fails`() {
            val eventData = """{"orderId":"order-123","customerId":"cust-wrong"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "orderId" to CorrelationDef(from = ".orderId"),
                    "customerId" to CorrelationDef(from = ".customerId")
                )
            )
            val established = mutableMapOf("orderId" to "order-123", "customerId" to "cust-456")

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }

        @Test
        fun `should not match when expect expression evaluation fails`() {
            val eventData = """{"customerId":"cust-789"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "customerId" to CorrelationDef(
                        from = ".customerId",
                        expect = $$"${.[[[invalid}"
                    )
                )
            )
            val context = buildJsonObject {
                put("customer", buildJsonObject { put("id", "cust-789") })
            }

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = context,
                    establishedCorrelations = null
                )
            )
        }

        @Test
        fun `should not match when from expression is malformed`() {
            val eventData = """{"orderId":"order-123"}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "orderId" to CorrelationDef(from = ".[[[invalid")
                )
            )
            val established = mutableMapOf<String, String>()

            assertFalse(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
        }
    }

    @Nested
    inner class EdgeCases {
        @Test
        fun `should handle event with no optional fields`() {
            val event = buildEvent()
            val filter = EventFilter(type = event.type)

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(filter)))
        }

        @Test
        fun `should handle filter with all null fields except type`() {
            val event = buildEvent(
                type = "test.event",
                subject = "some-subject",
                dataContentType = "application/json",
                dataSchema = "https://schema.example.com",
                time = OffsetDateTime.now()
            )

            // Filter only specifies type, all other fields are null (wildcard)
            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(type = "test.event"))))
        }

        @Test
        fun `should handle filter that matches missing event fields`() {
            val event = buildEvent()  // No subject, dataContentType, etc.

            // Filter expects specific subject, but event has none
            assertFalse(CloudEventMatcher.matchesFilters(event, listOf(EventFilter(subject = "expected"))))
        }

        @Test
        fun `should handle complex nested data in correlation extraction`() {
            val eventData = """{"order":{"items":[{"id":"item-1"},{"id":"item-2"}],"metadata":{"priority":"high"}}}"""
            val event = buildEvent(data = eventData.toByteArray(), dataContentType = "application/json")
            val filter = EventFilter(
                type = event.type,
                correlations = mapOf(
                    "priority" to CorrelationDef(from = ".order.metadata.priority")
                )
            )
            val established = mutableMapOf<String, String>()

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(filter),
                    correlationContext = null,
                    establishedCorrelations = established
                )
            )
            assertEquals("high", established["priority"])
        }

        @Test
        fun `should use custom event data provider`() {
            val event = buildEvent()
            val customData = buildJsonObject { put("custom", "data") }

            val filter = EventFilter(
                type = event.type,
                dataFilter = """.custom == "data""""
            )

            assertTrue(
                CloudEventMatcher.matchesFilters(event, listOf(filter)) { customData }
            )
        }

        @Test
        fun `should handle URN style source matching`() {
            val event = buildEvent(source = "urn:lemline:workflow:order-processor")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = "urn:lemline:workflow:order-processor"))
                )
            )

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = $$"${contains(\"order-processor\")}"))
                )
            )
        }

        @Test
        fun `should handle relative URI source matching`() {
            val event = buildEvent(source = "/workflows/my-workflow/instances/123")

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(source = $$"${startswith(\"/workflows/\")}"))
                )
            )
        }

        @Test
        fun `should not parse event data when filter does not match basic fields`() {
            val event = buildEvent(type = "test.event")
            var dataProviderCalled = false

            CloudEventMatcher.matchesFilters(
                event,
                listOf(EventFilter(type = "different.event"))
            ) {
                dataProviderCalled = true
                JsonNull
            }

            assertFalse(dataProviderCalled)
        }

        @Test
        fun `should parse event data when filter matches and has data filter`() {
            val event = buildEvent(type = "test.event")
            var dataProviderCalled = false
            val customData = buildJsonObject { put("status", "ok") }

            CloudEventMatcher.matchesFilters(
                event,
                listOf(EventFilter(type = "test.event", dataFilter = """.status == "ok""""))
            ) {
                dataProviderCalled = true
                customData
            }

            assertTrue(dataProviderCalled)
        }

        @Test
        fun `should parse event data when filter matches even without data filter`() {
            val event = buildEvent(type = "test.event")
            var dataProviderCalled = false

            CloudEventMatcher.matchesFilters(
                event,
                listOf(EventFilter(type = "test.event"))
            ) {
                dataProviderCalled = true
                buildJsonObject { }
            }

            assertTrue(dataProviderCalled)
        }

        @Test
        fun `should handle null source in event`() {
            val event = CloudEventBuilder.v1()
                .withId("test-id")
                .withSource(URI.create(""))
                .withType("test.event")
                .build()

            assertTrue(
                CloudEventMatcher.matchesFilters(
                    event,
                    listOf(EventFilter(type = "test.event"))
                )
            )
        }

        @Test
        fun `should handle event with all fields populated`() {
            val eventTime = OffsetDateTime.parse("2024-06-15T12:00:00Z")
            val eventData = """{"key":"value"}"""
            val event = buildEvent(
                id = "evt-123",
                source = "https://example.com/source",
                type = "com.example.full.event",
                subject = "/subject/path",
                dataContentType = "application/json",
                dataSchema = "https://schema.example.com/v1",
                time = eventTime,
                data = eventData.toByteArray()
            )

            val filter = EventFilter(
                type = "com.example.full.event",
                source = $$"${contains(\"example.com\")}",
                id = "evt-123",
                subject = "/subject/path",
                datacontenttype = "application/json",
                dataschema = $$"${endswith(\"v1\")}",
                time = $$"${. > \"2024-01-01T00:00:00Z\"}",
                dataFilter = """.key == "value""""
            )

            assertTrue(CloudEventMatcher.matchesFilters(event, listOf(filter)))
        }
    }
}
