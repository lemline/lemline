package com.lemline.core.activities.calls

import com.lemline.core.json.LemlineJson
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpCallTest {

    private fun createHttpCallWithMockEngine(handler: MockRequestHandler): HttpCall {
        val mockEngine = MockEngine(handler)
        val mockClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(LemlineJson.json)
            }
        }

        val httpCall = HttpCall()
        val clientField = HttpCall::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(httpCall, mockClient)

        return httpCall
    }

    @Test
    fun `test GET request with successful response`() = runTest {
        // Setup
        val jsonResponse = buildJsonObject { put("result", "success") }

        val httpCall = createHttpCallWithMockEngine { request ->
            // Verify request properties
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://example.com/api", request.url.toString())
            assertEquals("application/json", request.headers["Content-Type"])

            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "GET",
            endpoint = "https://example.com/api",
            headers = mapOf("Content-Type" to JsonPrimitive("application/json")),
            body = null
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test POST request with body and successful response`() = runTest {
        // Setup
        val requestBody = JsonObject(mapOf("name" to JsonPrimitive("test")))
        val jsonResponse = JsonObject(mapOf("id" to JsonPrimitive(123)))

        val httpCall = createHttpCallWithMockEngine { request ->
            // Verify request properties
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://example.com/api/create", request.url.toString())

            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "POST",
            endpoint = "https://example.com/api/create",
            headers = mapOf("Content-Type" to JsonPrimitive("application/json")),
            body = requestBody
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive(123), result["id"])
    }

    @Test
    fun `test PUT request with body and successful response`() = runTest {
        // Setup
        val requestBody = JsonObject(mapOf("name" to JsonPrimitive("updated")))
        val jsonResponse = JsonObject(mapOf("updated" to JsonPrimitive(true)))

        val httpCall = createHttpCallWithMockEngine { request ->
            // Verify request properties
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("https://example.com/api/update/123", request.url.toString())

            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "PUT",
            endpoint = "https://example.com/api/update/123",
            headers = mapOf("Content-Type" to JsonPrimitive("application/json")),
            body = requestBody
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive(true), result["updated"])
    }

    @Test
    fun `test DELETE request with successful response`() = runTest {
        // Setup
        val jsonResponse = buildJsonObject {
            put("deleted", true)
        }

        val httpCall = createHttpCallWithMockEngine { request ->
            // Verify request properties
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("https://example.com/api/delete/123", request.url.toString())

            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "DELETE",
            endpoint = "https://example.com/api/delete/123",
            headers = mapOf("Content-Type" to JsonPrimitive("application/json")),
            body = null
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive(true), result["deleted"])
    }

    @Test
    fun `test with query parameters`() = runTest {
        // Setup
        val jsonResponse = buildJsonObject {
            put("result", "success")
        }

        val httpCall = createHttpCallWithMockEngine { request ->
            // Verify request properties
            assertEquals(HttpMethod.Get, request.method)
            // The order of parameters might vary, so we check each parameter individually
            assertEquals("test", request.url.parameters["q"])
            assertEquals("1", request.url.parameters["page"])

            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "GET",
            endpoint = "https://example.com/api/search",
            headers = emptyMap(),
            body = null,
            query = mapOf("q" to JsonPrimitive("test"), "page" to JsonPrimitive(1))
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test with raw output format`() = runTest {
        // Setup
        val responseText = """{"result":"success"}"""

        val httpCall = createHttpCallWithMockEngine { _ ->
            // Return mock response
            respond(
                content = responseText,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "GET",
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null,
            output = "raw"
        )

        // Verify
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(responseText.toByteArray())
        assertEquals(JsonPrimitive(expectedBase64), result)
    }

    @Test
    fun `test with response output format`() = runTest {
        // Setup
        val jsonResponse = buildJsonObject {
            put("result", "success")
        }

        val httpCall = createHttpCallWithMockEngine { request ->
            // Return mock response
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute
        val result = httpCall.execute(
            method = "GET",
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null,
            output = "response"
        ) as JsonObject

        // Verify
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test with HTTP error response`() = runTest {
        // Setup
        val httpCall = createHttpCallWithMockEngine { request ->
            // Return mock error response
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
            )
        }

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/nonexistent",
                headers = emptyMap(),
                body = null
            )
        }

        // Verify the exception has the correct message
        assert(exception.message?.contains("404") == true)
    }

    @Test
    fun `test with redirect parameter false and redirection status code`() = runTest {
        // Setup
        val httpCall = createHttpCallWithMockEngine { _ ->
            // Return mock redirection response
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://example.com/new-location")
            )
        }

        // Execute and verify - with redirect=false, 3xx should be an error
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/redirect",
                headers = emptyMap(),
                body = null,
                redirect = false
            )
        }

        // Verify the exception has the correct message
        assert(exception.message?.contains("302") == true)
    }

    @Test
    fun `test actual redirect following with redirect parameter true`() = runTest {
        // This test verifies that redirects are actually followed when redirect=true
        // Setup - first response is a redirect, second response is the final content
        val finalJsonResponse = buildJsonObject {
            put("result", "redirected successfully")
        }

        var requestCount = 0

        val httpCall = createHttpCallWithMockEngine { request ->
            requestCount++

            if (requestCount == 1) {
                // First request - return a redirect to another URL
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location, "https://example.com/redirected"
                    )
                )
            } else {
                // Second request (after redirect) - verify we're at the new URL and return content
                assertEquals("https://example.com/redirected", request.url.toString())
                respond(
                    content = finalJsonResponse.toString(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                )
            }
        }

        // Execute with redirect=true to follow redirects
        val result = httpCall.execute(
            method = "GET",
            endpoint = "https://example.com/api/original",
            headers = emptyMap(),
            body = null,
            redirect = true
        ) as JsonObject

        // Verify both requests were made (the redirect was followed)
        assertEquals(2, requestCount)
        assertEquals(JsonPrimitive("redirected successfully"), result["result"])
    }

    @Test
    fun `test with unsupported HTTP method`() = runTest {
        // Setup
        val httpCall = createHttpCallWithMockEngine { request ->
            // This should not be called because the method validation happens before the request
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = "PATCH", // Not supported in the implementation
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null
            )
        }

        // Verify the exception has the correct message
        assert(exception.message?.contains("Unsupported HTTP method") == true)
    }

    @Test
    fun `test with unsupported output format`() = runTest {
        // Setup
        val httpCall = createHttpCallWithMockEngine { request ->
            // Return mock response
            respond(
                content = "{}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                output = "invalid" // Not supported
            )
        }

        // Verify the exception has the correct message
        assert(exception.message?.contains("Unsupported output format") == true)
    }
}
