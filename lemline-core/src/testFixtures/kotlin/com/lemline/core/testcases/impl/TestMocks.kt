package com.lemline.core.testcases.impl

import com.lemline.core.activities.mock.EmitMockMatcher
import com.lemline.core.activities.mock.EmitMockResponse
import com.lemline.core.activities.mock.EmitMockRule
import com.lemline.core.activities.mock.HttpMockMatcher
import com.lemline.core.activities.mock.HttpMockResponse
import com.lemline.core.activities.mock.HttpMockRule
import com.lemline.core.activities.mock.ListenMockMatcher
import com.lemline.core.activities.mock.ListenMockResponse
import com.lemline.core.activities.mock.ListenMockRule
import com.lemline.core.activities.mock.MockConfiguration
import com.lemline.core.activities.mock.ScriptMockMatcher
import com.lemline.core.activities.mock.ScriptMockResponse
import com.lemline.core.activities.mock.ScriptMockRule
import com.lemline.core.activities.mock.ShellMockMatcher
import com.lemline.core.activities.mock.ShellMockResponse
import com.lemline.core.activities.mock.ShellMockRule
import io.cloudevents.CloudEvent
import io.cloudevents.core.builder.CloudEventBuilder
import java.net.URI
import java.time.OffsetDateTime
import java.util.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared mock configurations for test cases.
 *
 * These mock configurations simulate external services like JSONPlaceholder API
 * and local script/shell execution for deterministic testing.
 */
object TestMocks {

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP Mocks - Simulates JSONPlaceholder API
    // ─────────────────────────────────────────────────────────────────────────

    /** Mock response for GET /posts/1 */
    internal val post1Response = buildJsonObject {
        put("id", 1)
        put("title", "sunt aut facere repellat provident occaecati excepturi optio reprehenderit")
        put("body", "quia et suscipit\nsuscipit recusandae consequuntur")
        put("userId", 1)
    }

    /** Mock response for GET /posts/2 */
    internal val post2Response = buildJsonObject {
        put("id", 2)
        put("title", "qui est esse")
        put("body", "est rerum tempore vitae")
        put("userId", 1)
    }

    /** Mock response for GET /comments?postId=1 */
    internal val commentsForPost1 = buildJsonArray {
        add(buildJsonObject {
            put("id", 1)
            put("postId", 1)
            put("name", "id labore ex et quam laborum")
            put("email", "Eliseo@gardner.biz")
            put("body", "laudantium enim quasi")
        })
        add(buildJsonObject {
            put("id", 2)
            put("postId", 1)
            put("name", "quo vero reiciendis velit similique earum")
            put("email", "Jayne_Kuhic@sydney.com")
            put("body", "est natus enim nihil est dolore omnis voluptatem")
        })
    }

    /** Mock response for POST /posts (created post) */
    internal val createdPostResponse = buildJsonObject {
        put("id", 101)
        put("title", "Test Post")
        put("body", "This is a test post")
        put("userId", 1)
    }

    /** Mock response for PUT /posts/1 (updated post) */
    internal val updatedPostResponse = buildJsonObject {
        put("id", 1)
        put("title", "Updated Title")
        put("body", "Updated body")
        put("userId", 1)
    }

    /** Mock response for DELETE /posts/1 (empty on success) */
    internal val deleteResponse = buildJsonObject { }

    /** Mock response for full response output format */
    internal val fullResponseOutput = buildJsonObject {
        put("request", buildJsonObject {
            put("method", "GET")
            put("uri", "https://jsonplaceholder.typicode.com/posts/1")
            put("headers", buildJsonObject { })
        })
        put("statusCode", 200)
        put("headers", buildJsonObject {
            put("Content-Type", "application/json")
        })
        put("content", post1Response)
    }

    /** Mock response for GET /users/1 */
    internal val user1Response = buildJsonObject {
        put("id", 1)
        put("name", "Leanne Graham")
        put("username", "Bret")
        put("email", "Sincere@april.biz")
    }

    /** HTTP mock rules for JSONPlaceholder API */
    val httpMocks = listOf(
        // GET /posts/1
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/posts/1", method = "GET"),
            response = HttpMockResponse(status = 200, body = post1Response)
        ),
        // GET /posts/2
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/posts/2", method = "GET"),
            response = HttpMockResponse(status = 200, body = post2Response)
        ),
        // GET /users/1
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/users/1", method = "GET"),
            response = HttpMockResponse(status = 200, body = user1Response)
        ),
        // GET /comments?postId=1
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/comments*", method = "GET"),
            response = HttpMockResponse(status = 200, body = commentsForPost1)
        ),
        // POST /posts
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/posts", method = "POST"),
            response = HttpMockResponse(status = 201, body = createdPostResponse)
        ),
        // PUT /posts/1
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/posts/1", method = "PUT"),
            response = HttpMockResponse(status = 200, body = updatedPostResponse)
        ),
        // DELETE /posts/1
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*/posts/1", method = "DELETE"),
            response = HttpMockResponse(status = 200, body = deleteResponse)
        ),
        // Catch-all for other GETs to jsonplaceholder
        HttpMockRule(
            match = HttpMockMatcher(url = "*jsonplaceholder*", method = "GET"),
            response = HttpMockResponse(status = 200, body = post1Response)
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Script Mocks - JavaScript and Python
    // ─────────────────────────────────────────────────────────────────────────

    /** Script mock rules - returns stdout as string (JsonPrimitive) */
    val scriptMocks = listOf(
        // JavaScript - returns stdout string
        ScriptMockRule(
            match = ScriptMockMatcher(language = "javascript"),
            response = ScriptMockResponse(
                output = JsonPrimitive("mocked js output"),
                exitCode = 0
            )
        ),
        ScriptMockRule(
            match = ScriptMockMatcher(language = "js"),
            response = ScriptMockResponse(
                output = JsonPrimitive("mocked js output"),
                exitCode = 0
            )
        ),
        // Python - returns stdout string
        ScriptMockRule(
            match = ScriptMockMatcher(language = "python"),
            response = ScriptMockResponse(
                output = JsonPrimitive("mocked python output"),
                exitCode = 0
            )
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Shell Mocks
    // ─────────────────────────────────────────────────────────────────────────

    /** Shell mock rules */
    val shellMocks = listOf(
        // echo command
        ShellMockRule(
            match = ShellMockMatcher(command = "echo*"),
            response = ShellMockResponse(stdout = "Hello World", exitCode = 0)
        ),
        // ls command
        ShellMockRule(
            match = ShellMockMatcher(command = "ls*"),
            response = ShellMockResponse(stdout = "file1.txt\nfile2.txt\ndir1", exitCode = 0)
        ),
        // pwd command
        ShellMockRule(
            match = ShellMockMatcher(command = "pwd"),
            response = ShellMockResponse(stdout = "/home/user/project", exitCode = 0)
        ),
        // cat command
        ShellMockRule(
            match = ShellMockMatcher(command = "cat*"),
            response = ShellMockResponse(stdout = "file contents", exitCode = 0)
        ),
        // Catch-all for other commands
        ShellMockRule(
            match = ShellMockMatcher(command = "*"),
            response = ShellMockResponse(stdout = "mocked output", exitCode = 0)
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Emit Mocks
    // ─────────────────────────────────────────────────────────────────────────

    /** Emit mock rules - catch-all for any emit event */
    val emitMocks = listOf(
        // Catch-all - emit returns input unchanged (fire-and-forget semantic)
        EmitMockRule(
            match = EmitMockMatcher(),
            response = EmitMockResponse()
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Listen Mocks - CloudEvent delivery simulation
    // ─────────────────────────────────────────────────────────────────────────

    /** Mock CloudEvent data for order.created events */
    internal val orderCreatedData = buildJsonObject {
        put("orderId", "ORD-12345")
        put("customerId", "CUST-001")
        put("total", 99.99)
        put("currency", "USD")
        put("items", buildJsonArray {
            add(buildJsonObject {
                put("productId", "PROD-001")
                put("quantity", 2)
                put("price", 49.99)
            })
        })
    }

    /** Mock CloudEvent data for user.registered events */
    internal val userRegisteredData = buildJsonObject {
        put("userId", "USR-98765")
        put("email", "user@example.com")
        put("name", "John Doe")
        put("registeredAt", "2024-01-15T10:30:00Z")
    }

    /** Mock CloudEvent data for payment.completed events */
    internal val paymentCompletedData = buildJsonObject {
        put("paymentId", "PAY-54321")
        put("orderId", "ORD-12345")
        put("amount", 99.99)
        put("currency", "USD")
        put("status", "completed")
    }

    /** Mock CloudEvent data for sensor.reading events */
    internal val sensorReadingData = buildJsonObject {
        put("sensorId", "SENSOR-001")
        put("temperature", 23.5)
        put("humidity", 65)
        put("timestamp", "2024-01-15T12:00:00Z")
    }

    /** Generic event for wildcard matching */
    internal val genericData = buildJsonObject {
        put("type", "generic")
        put("data", "mock event data")
        put("timestamp", "2024-01-15T12:00:00Z")
    }

    internal val lowTemperatureData = buildJsonObject {
        put("sensorId", "TEMP-002")
        put("temperature", 18.0)
        put("unit", "celsius")
    }

    internal val highTemperatureData = buildJsonObject {
        put("sensorId", "TEMP-001")
        put("temperature", 42.5)
        put("unit", "celsius")
    }

    /** Listen mock rules */
    val listenMocks = listOf(
        // order.created events
        ListenMockRule(
            match = ListenMockMatcher(type = "order.created"),
            response = ListenMockResponse(data = orderCreatedData)
        ),
        // user.registered events
        ListenMockRule(
            match = ListenMockMatcher(type = "user.registered"),
            response = ListenMockResponse(data = userRegisteredData)
        ),
        // payment.completed events
        ListenMockRule(
            match = ListenMockMatcher(type = "payment.completed"),
            response = ListenMockResponse(data = paymentCompletedData)
        ),
        // sensor.reading events
        ListenMockRule(
            match = ListenMockMatcher(type = "sensor.reading"),
            response = ListenMockResponse(data = sensorReadingData)
        ),
        // Catch-all for any event type
        ListenMockRule(
            match = ListenMockMatcher(),
            response = ListenMockResponse(data = genericData)
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Combined Configurations
    // ─────────────────────────────────────────────────────────────────────────

    /** Full mock configuration for emit tests */
    val emitConfig = MockConfiguration(emitMocks = emitMocks)

    /** Full mock configuration for listen tests */
    val listenConfig = MockConfiguration(listenMocks = listenMocks)

    /** Full mock configuration for HTTP tests */
    val httpConfig = MockConfiguration(httpMocks = httpMocks)

    /** Full mock configuration for script tests */
    val scriptConfig = MockConfiguration(scriptMocks = scriptMocks)

    /** Full mock configuration for shell tests */
    val shellConfig = MockConfiguration(shellMocks = shellMocks)

    // ─────────────────────────────────────────────────────────────────────────
    // CloudEvent Builders - For listen task tests
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Helper to build a CloudEvent from type and JSON data.
     */
    private fun buildCloudEvent(type: String, data: JsonElement): CloudEvent {
        return CloudEventBuilder.v1()
            .withId(UUID.randomUUID().toString())
            .withSource(URI.create("https://test.example.com"))
            .withType(type)
            .withData("application/json", data.toString().toByteArray())
            .build()
    }

    /**
     * Comprehensive CloudEvent builder with all attributes.
     */
    fun buildCloudEvent(
        type: String,
        data: JsonElement,
        id: String = UUID.randomUUID().toString(),
        source: URI = URI.create("https://test.example.com"),
        subject: String? = null,
        time: OffsetDateTime? = null,
        dataContentType: String = "application/json",
        dataSchema: URI? = null
    ): CloudEvent {
        val builder = CloudEventBuilder.v1()
            .withId(id)
            .withSource(source)
            .withType(type)
            .withDataContentType(dataContentType)
            .withData(dataContentType, data.toString().toByteArray())

        subject?.let { builder.withSubject(it) }
        time?.let { builder.withTime(it) }
        dataSchema?.let { builder.withDataSchema(it) }

        return builder.build()
    }

    /** CloudEvent for order.created */
    val orderCreatedCloudEvent: CloudEvent = buildCloudEvent("order.created", orderCreatedData)

    /** CloudEvent for user.registered */
    val userRegisteredCloudEvent: CloudEvent = buildCloudEvent("user.registered", userRegisteredData)

    /** CloudEvent for payment.completed */
    val paymentCompletedCloudEvent: CloudEvent = buildCloudEvent("payment.completed", paymentCompletedData)

    /** CloudEvent for sensor.reading */
    val sensorReadingCloudEvent: CloudEvent = buildCloudEvent("sensor.reading", sensorReadingData)

    // ─────────────────────────────────────────────────────────────────────────
    // Specialized CloudEvents for Comprehensive Filter Testing
    // ─────────────────────────────────────────────────────────────────────────

    /** CloudEvent with specific ID for id filter tests */
    val eventWithSpecificId: CloudEvent = buildCloudEvent(
        type = "test.event",
        data = buildJsonObject { put("message", "test") },
        id = "specific-event-id-12345"
    )

    /** CloudEvent with specific source for source filter tests */
    val eventWithSpecificSource: CloudEvent = buildCloudEvent(
        type = "test.event",
        data = buildJsonObject { put("message", "from-orders") },
        source = URI.create("https://orders.example.com/api")
    )

    /** CloudEvent with specific subject for subject filter tests */
    val eventWithSubject: CloudEvent = buildCloudEvent(
        type = "order.shipped",
        data = buildJsonObject {
            put("orderId", "ORD-999")
            put("carrier", "FedEx")
        },
        subject = "order/ORD-999"
    )

    /** CloudEvent with specific time for time filter tests */
    val eventWithTime: CloudEvent = buildCloudEvent(
        type = "scheduled.task",
        data = buildJsonObject { put("taskId", "TASK-001") },
        time = OffsetDateTime.parse("2024-06-15T14:30:00Z")
    )

    /** CloudEvent with specific dataschema for dataschema filter tests */
    val eventWithDataSchema: CloudEvent = buildCloudEvent(
        type = "validated.event",
        data = buildJsonObject {
            put("name", "John")
            put("age", 30)
        },
        dataSchema = URI.create("https://schemas.example.com/person/v1")
    )

    /** CloudEvent with XML content type for datacontenttype filter tests */
    val eventWithXmlContentType: CloudEvent = buildCloudEvent(
        type = "xml.data",
        data = buildJsonObject { put("format", "xml") },
        dataContentType = "application/xml"
    )

    /** High temperature sensor event for data expression filter tests */
    val highTemperatureEvent: CloudEvent = buildCloudEvent(
        type = "sensor.reading",
        data = highTemperatureData
    )

    /** Low temperature sensor event for data expression filter tests */
    val lowTemperatureEvent: CloudEvent = buildCloudEvent(
        type = "sensor.reading",
        data = lowTemperatureData
    )

    /** Critical alert event for data expression filter tests */
    val criticalAlertEvent: CloudEvent = buildCloudEvent(
        type = "alert.triggered",
        data = buildJsonObject {
            put("alertId", "ALERT-001")
            put("severity", "critical")
            put("source", "monitoring")
        }
    )

    /** Warning alert event for data expression filter tests */
    val warningAlertEvent: CloudEvent = buildCloudEvent(
        type = "alert.triggered",
        data = buildJsonObject {
            put("alertId", "ALERT-002")
            put("severity", "warning")
            put("source", "monitoring")
        }
    )

    /** Stop/termination event for until tests */
    val stopMonitoringEvent: CloudEvent = buildCloudEvent(
        type = "monitoring.stopped",
        data = buildJsonObject {
            put("reason", "shift-ended")
            put("timestamp", "2024-06-15T18:00:00Z")
        }
    )

    /** First reading in a series for until expression tests */
    val reading1Event: CloudEvent = buildCloudEvent(
        type = "sensor.reading",
        data = buildJsonObject {
            put("readingId", 1)
            put("value", 10)
        }
    )

    /** Second reading in a series for until expression tests */
    val reading2Event: CloudEvent = buildCloudEvent(
        type = "sensor.reading",
        data = buildJsonObject {
            put("readingId", 2)
            put("value", 25)
        }
    )

    /** Third reading that triggers threshold for until expression tests */
    val reading3ThresholdEvent: CloudEvent = buildCloudEvent(
        type = "sensor.reading",
        data = buildJsonObject {
            put("readingId", 3)
            put("value", 150)
        }
    )
}
