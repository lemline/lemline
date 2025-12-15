// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.testcases

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
import java.util.UUID
import kotlinx.serialization.json.JsonArray
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
    private val post1Response = buildJsonObject {
        put("id", 1)
        put("title", "sunt aut facere repellat provident occaecati excepturi optio reprehenderit")
        put("body", "quia et suscipit\nsuscipit recusandae consequuntur")
        put("userId", 1)
    }

    /** Mock response for GET /posts/2 */
    private val post2Response = buildJsonObject {
        put("id", 2)
        put("title", "qui est esse")
        put("body", "est rerum tempore vitae")
        put("userId", 1)
    }

    /** Mock response for GET /comments?postId=1 */
    private val commentsForPost1 = buildJsonArray {
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
            put("body", "est natus enim nihil est dolore omnis voluptatem"  )
        })
    }

    /** Mock response for POST /posts (created post) */
    private val createdPostResponse = buildJsonObject {
        put("id", 101)
        put("title", "Test Post")
        put("body", "This is a test post")
        put("userId", 1)
    }

    /** Mock response for PUT /posts/1 (updated post) */
    private val updatedPostResponse = buildJsonObject {
        put("id", 1)
        put("title", "Updated Title")
        put("body", "Updated body")
        put("userId", 1)
    }

    /** Mock response for DELETE /posts/1 (empty on success) */
    private val deleteResponse = buildJsonObject { }

    /** Mock response for full response output format */
    private val fullResponseOutput = buildJsonObject {
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
    private val user1Response = buildJsonObject {
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
    private val orderCreatedEvent = buildJsonObject {
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
    private val userRegisteredEvent = buildJsonObject {
        put("userId", "USR-98765")
        put("email", "user@example.com")
        put("name", "John Doe")
        put("registeredAt", "2024-01-15T10:30:00Z")
    }

    /** Mock CloudEvent data for payment.completed events */
    private val paymentCompletedEvent = buildJsonObject {
        put("paymentId", "PAY-54321")
        put("orderId", "ORD-12345")
        put("amount", 99.99)
        put("currency", "USD")
        put("status", "completed")
    }

    /** Mock CloudEvent data for sensor.reading events */
    private val sensorReadingEvent = buildJsonObject {
        put("sensorId", "SENSOR-001")
        put("temperature", 23.5)
        put("humidity", 65)
        put("timestamp", "2024-01-15T12:00:00Z")
    }

    /** Generic event for wildcard matching */
    private val genericEvent = buildJsonObject {
        put("type", "generic")
        put("data", "mock event data")
        put("timestamp", "2024-01-15T12:00:00Z")
    }

    /** Listen mock rules */
    val listenMocks = listOf(
        // order.created events
        ListenMockRule(
            match = ListenMockMatcher(type = "order.created"),
            response = ListenMockResponse(data = orderCreatedEvent)
        ),
        // user.registered events
        ListenMockRule(
            match = ListenMockMatcher(type = "user.registered"),
            response = ListenMockResponse(data = userRegisteredEvent)
        ),
        // payment.completed events
        ListenMockRule(
            match = ListenMockMatcher(type = "payment.completed"),
            response = ListenMockResponse(data = paymentCompletedEvent)
        ),
        // sensor.reading events
        ListenMockRule(
            match = ListenMockMatcher(type = "sensor.reading"),
            response = ListenMockResponse(data = sensorReadingEvent)
        ),
        // Catch-all for any event type
        ListenMockRule(
            match = ListenMockMatcher(),
            response = ListenMockResponse(data = genericEvent)
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

    /** Combined mock configuration for tests that use multiple activity types */
    val allMocks = MockConfiguration(
        emitMocks = emitMocks,
        listenMocks = listenMocks,
        httpMocks = httpMocks,
        scriptMocks = scriptMocks,
        shellMocks = shellMocks
    )

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

    /** CloudEvent for order.created */
    val orderCreatedCloudEvent: CloudEvent = buildCloudEvent("order.created", orderCreatedEvent)

    /** CloudEvent for user.registered */
    val userRegisteredCloudEvent: CloudEvent = buildCloudEvent("user.registered", userRegisteredEvent)

    /** CloudEvent for payment.completed */
    val paymentCompletedCloudEvent: CloudEvent = buildCloudEvent("payment.completed", paymentCompletedEvent)

    /** CloudEvent for sensor.reading */
    val sensorReadingCloudEvent: CloudEvent = buildCloudEvent("sensor.reading", sensorReadingEvent)

    /** CloudEvent for generic/wildcard matching */
    val genericCloudEvent: CloudEvent = buildCloudEvent("some.unknown.type", genericEvent)
}
