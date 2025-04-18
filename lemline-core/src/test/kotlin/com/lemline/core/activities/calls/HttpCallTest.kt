package com.lemline.core.activities.calls

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.client.Invocation
import jakarta.ws.rs.client.WebTarget
import jakarta.ws.rs.core.Response
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(MockKExtension::class)
class HttpCallTest {

    private lateinit var httpCall: HttpCall
    private lateinit var mockClient: Client
    private lateinit var mockWebTarget: WebTarget
    private lateinit var mockBuilder: Invocation.Builder
    private lateinit var mockResponse: Response

    @BeforeEach
    fun setup() {
        // Mock the JAX-RS client components
        mockClient = mockk<Client>()
        mockWebTarget = mockk<WebTarget>()
        mockBuilder = mockk<Invocation.Builder>()
        mockResponse = mockk<Response>()

        // Create a real HttpCall but with a mocked client
        httpCall = HttpCall()
        val clientField = HttpCall::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(httpCall, mockClient)

        // Setup common mock behaviors
        every { mockClient.target(any<String>()) } returns mockWebTarget
        every { mockWebTarget.queryParam(any(), any()) } returns mockWebTarget
        every { mockWebTarget.request() } returns mockBuilder
        every { mockBuilder.header(any(), any()) } returns mockBuilder
    }

    @Test
    fun `test GET request with successful response`() {
        // Setup
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")

        // Mock response
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api",
            headers = mapOf("Content-Type" to "application/json"),
            body = null
        )
        val result = future.get()

        // Verify
        assertEquals("success", result.get("result").asText())
        verify { mockClient.target("https://example.com/api") }
        verify { mockWebTarget.queryParam("redirect", "false") }
        verify { mockBuilder.header("Content-Type", "application/json") }
        verify { mockBuilder.get() }
    }

    @Test
    fun `test POST request with body and successful response`() {
        // Setup
        val requestBody = JsonNodeFactory.instance.objectNode().put("name", "test")
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("id", 123)

        // Mock response
        every { mockBuilder.post(any<Entity<JsonNode>>()) } returns mockResponse
        every { mockResponse.status } returns 201
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.POST,
            endpoint = "https://example.com/api/create",
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody
        )
        val result = future.get()

        // Verify
        assertEquals(123, result.get("id").asInt())
        verify { mockClient.target("https://example.com/api/create") }
        verify {
            mockBuilder.post(match {
                it.entity == requestBody && it.mediaType.toString() == "application/json"
            })
        }
    }

    @Test
    fun `test PUT request with body and successful response`() {
        // Setup
        val requestBody = JsonNodeFactory.instance.objectNode().put("name", "updated")
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("updated", true)

        // Mock response
        every { mockBuilder.put(any<Entity<JsonNode>>()) } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.PUT,
            endpoint = "https://example.com/api/update/123",
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody
        )
        val result = future.get()

        // Verify
        assertEquals(true, result.get("updated").asBoolean())
        verify { mockClient.target("https://example.com/api/update/123") }
        verify {
            mockBuilder.put(match {
                it.entity == requestBody && it.mediaType.toString() == "application/json"
            })
        }
    }

    @Test
    fun `test DELETE request with successful response`() {
        // Setup
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("deleted", true)

        // Mock response
        every { mockBuilder.delete() } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.DELETE,
            endpoint = "https://example.com/api/delete/123",
            headers = mapOf("Content-Type" to "application/json"),
            body = null
        )
        val result = future.get()

        // Verify
        assertEquals(true, result.get("deleted").asBoolean())
        verify { mockClient.target("https://example.com/api/delete/123") }
        verify { mockBuilder.delete() }
    }

    @Test
    fun `test with query parameters`() {
        // Setup
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")

        // Mock response
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api/search",
            headers = emptyMap(),
            body = null,
            query = mapOf("q" to "test", "page" to 1)
        )
        val result = future.get()

        // Verify
        assertEquals("success", result.get("result").asText())
        verify { mockClient.target("https://example.com/api/search") }
        verify { mockWebTarget.queryParam("q", "test") }
        verify { mockWebTarget.queryParam("page", 1) }
    }

    @Test
    fun `test with raw output format`() {
        // Setup
        val responseText = """{"result":"success"}"""

        // Mock response
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(String::class.java) } returns responseText

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null,
            output = "raw"
        )
        val result = future.get()

        // Verify
        val expectedBase64 = java.util.Base64.getEncoder().encodeToString(responseText.toByteArray())
        assertEquals(expectedBase64, result.asText())
    }

    @Test
    fun `test with response output format`() {
        // Setup
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")

        // Mock response
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 200
        every { mockResponse.readEntity(JsonNode::class.java) } returns jsonResponse

        // Execute
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null,
            output = "response"
        )
        val result = future.get()

        // Verify
        assertEquals("success", result.get("result").asText())
    }

    @Test
    fun `test with HTTP error response`() {
        // Setup
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 404
        every { mockResponse.readEntity(String::class.java) } returns "Not Found"

        // Execute and verify
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api/nonexistent",
            headers = emptyMap(),
            body = null
        )

        // The future should complete exceptionally
        val exception = assertFailsWith<java.util.concurrent.ExecutionException> {
            future.get()
        }

        // Verify the cause is a RuntimeException with the correct message
        assert(exception.cause is RuntimeException)
        assert(exception.cause?.message?.contains("404") == true)
    }

    @Test
    fun `test with unsupported HTTP method`() {
        // Execute and verify
        val future = httpCall.execute(
            method = "PATCH", // Not supported in the implementation
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null
        )

        // The future should complete exceptionally
        val exception = assertFailsWith<java.util.concurrent.ExecutionException> {
            future.get()
        }

        // Verify the cause is an IllegalArgumentException with the correct message
        assert(exception.cause is IllegalArgumentException)
        assert(exception.cause?.message?.contains("Unsupported HTTP method") == true)
    }

    @Test
    fun `test with unsupported output format`() {
        // Setup
        every { mockBuilder.get() } returns mockResponse
        every { mockResponse.status } returns 200

        // Execute and verify
        val future = httpCall.execute(
            method = HttpMethod.GET,
            endpoint = "https://example.com/api",
            headers = emptyMap(),
            body = null,
            output = "invalid" // Not supported
        )

        // The future should complete exceptionally
        val exception = assertFailsWith<java.util.concurrent.ExecutionException> {
            future.get()
        }

        // Verify the cause is an IllegalArgumentException with the correct message
        assert(exception.cause is IllegalArgumentException)
        assert(exception.cause?.message?.contains("Unsupported output format") == true)
    }
}
