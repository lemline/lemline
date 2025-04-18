package com.lemline.core.nodes.activities

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.errors.WorkflowException
import com.lemline.core.expressions.JQExpression
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.NodePosition
import io.mockk.*
import io.mockk.junit5.MockKExtension
import io.serverlessworkflow.api.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@ExtendWith(MockKExtension::class)
class CallHttpInstanceTest {

    private lateinit var mockHttpCall: HttpCall
    private lateinit var mockParent: NodeInstance<*>
    private lateinit var mockNode: Node<CallHTTP>
    private lateinit var mockCallHTTP: CallHTTP
    private lateinit var mockHttpArgs: HTTPArguments
    private lateinit var mockEndpoint: Endpoint
    private lateinit var mockHeaders: HTTPHeaders
    private lateinit var mockQuery: HTTPQuery
    private lateinit var callHttpInstance: CallHttpInstance

    @BeforeEach
    fun setup() {
        // Mock the HttpCall class
        mockHttpCall = mockk<HttpCall>()

        // Mock the parent NodeInstance
        mockParent = mockk<NodeInstance<*>>()

        // Mock the Node and CallHTTP
        mockNode = mockk<Node<CallHTTP>>()
        mockCallHTTP = mockk<CallHTTP>()
        mockHttpArgs = mockk<HTTPArguments>()
        mockEndpoint = mockk<Endpoint>()
        mockHeaders = mockk<HTTPHeaders>()
        mockQuery = mockk<HTTPQuery>()

        // Setup common mock behaviors
        every { mockNode.task } returns mockCallHTTP
        every { mockNode.name } returns "testHttpCall"
        every { mockNode.position } returns NodePosition(listOf("test"))
        every { mockCallHTTP.with } returns mockHttpArgs
        every { mockHttpArgs.method } returns "GET"
        every { mockHttpArgs.endpoint } returns mockEndpoint
        every { mockHttpArgs.headers } returns mockHeaders
        every { mockHttpArgs.query } returns mockQuery
        every { mockHttpArgs.body } returns null
        every { mockHttpArgs.output } returns null
        every { mockHttpArgs.isRedirect } returns false

        // Create the CallHttpInstance with mocked dependencies
        callHttpInstance = CallHttpInstance(mockNode, mockParent)

        // Replace the HttpCall with our mock
        val httpCallField = CallHttpInstance::class.java.getDeclaredField("httpCall")
        httpCallField.isAccessible = true
        httpCallField.set(callHttpInstance, mockHttpCall)

        // Setup input for the instance
        val jsonInput = JsonObject(mapOf("key" to JsonPrimitive("value")))
        callHttpInstance.rawInput = jsonInput
    }

    @Test
    fun `test execute with simple endpoint`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns mapOf("Content-Type" to "application/json")
        every { mockQuery.getAdditionalProperties() } returns mapOf("param" to "value")

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = mapOf("Content-Type" to "application/json"),
                body = null,
                query = mapOf("param" to "value"),
                output = "content",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = mapOf("Content-Type" to "application/json"),
                body = null,
                query = mapOf("param" to "value"),
                output = "content",
                redirect = false
            )
        }

        // Verify the output was set
        val expectedOutput = LemlineJson.jacksonMapper.convertValue(jsonResponse, JsonElement::class.java)
        assertEquals(expectedOutput, callHttpInstance.rawOutput)
    }

    @Test
    fun `test execute with URI endpoint`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val uri = URI("https://example.com/api")
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns uri

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        }
    }

    @Test
    fun `test execute with EndpointConfiguration`() = runTest {
        // Setup
        val endpointConfig = mockk<EndpointConfiguration>()
        val endpointUri = mockk<EndpointUri>()
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns endpointConfig
        every { endpointConfig.uri } returns endpointUri
        every { endpointUri.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api/config"

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/config",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/config",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        }
    }

    @Test
    fun `test execute with runtime expression endpoint`() = runTest {
        // Setup
        val runtimeExpr = "\${.apiUrl}"
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns runtimeExpr

        // Mock expression evaluation
        mockkObject(JQExpression)
        every {
            JQExpression.eval(any<JsonElement>(), eq(".apiUrl"), any<JsonObject>())
        } answers { JsonPrimitive("https://example.com/api/dynamic") }

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/dynamic",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api/dynamic",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        }

        unmockkObject(JQExpression)
    }

    @Test
    fun `test execute with body`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val requestBody = mapOf("name" to "test")
        val jsonNode = JsonNodeFactory.instance.objectNode().put("name", "test")
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("id", 123)
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock body
        every { mockHttpArgs.body } returns requestBody
        every { LemlineJson.jacksonMapper.convertValue(requestBody, JsonNode::class.java) } returns jsonNode

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = jsonNode,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = jsonNode,
                query = emptyMap(),
                output = "content",
                redirect = false
            )
        }
    }

    @Test
    fun `test execute with output format`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val outputFormat = mockk<HTTPArguments.HTTPOutput>()
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock output format
        every { mockHttpArgs.output } returns outputFormat
        every { outputFormat.value() } returns "raw"

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "raw",
                redirect = false
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "raw",
                redirect = false
            )
        }
    }

    @Test
    fun `test execute with redirect flag`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonNodeFactory.instance.objectNode().put("result", "success")
        val future = CompletableFuture.completedFuture(jsonResponse as JsonNode)

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock redirect flag
        every { mockHttpArgs.isRedirect } returns true

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution
        every {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = true
            )
        } returns future

        // Execute
        callHttpInstance.execute()

        // Verify
        verify {
            mockHttpCall.execute(
                method = "GET",
                endpoint = "https://example.com/api",
                headers = emptyMap(),
                body = null,
                query = emptyMap(),
                output = "content",
                redirect = true
            )
        }
    }

    @Test
    fun `test error handling for unsupported endpoint type`() = runTest {
        // Setup
        every { mockEndpoint.get() } returns 123 // Unsupported type

        // Execute and verify
        val exception = assertFailsWith<WorkflowException> {
            callHttpInstance.execute()
        }

        // Verify the exception details
        assert(exception.error.type.contains("expression"))
        assert(exception.error.title?.contains("Unsupported Endpoint type") == true)
    }

    @Test
    fun `test error handling for unsupported UriTemplate type`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()

        // Mock endpoint resolution with unsupported type
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns 123 // Unsupported type

        // Execute and verify
        val exception = assertFailsWith<WorkflowException> {
            callHttpInstance.execute()
        }

        // Verify the exception details
        assert(exception.error.type.contains("expression"))
        assert(exception.error.title?.contains("Unsupported UriTemplate type") == true)
    }

    @Test
    fun `test error handling for runtime expression evaluation failure`() = runTest {
        // Setup
        val runtimeExpr = "\${.apiUrl}"

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns runtimeExpr

        // Mock expression evaluation to throw exception
        mockkObject(JQExpression)
        every {
            JQExpression.eval(any<JsonElement>(), any<String>(), any<JsonObject>())
        } throws RuntimeException("Expression evaluation failed")

        // Execute and verify
        val exception = assertFailsWith<WorkflowException> {
            callHttpInstance.execute()
        }

        // Verify the exception details
        assert(exception.error.type.contains("expression"))

        unmockkObject(JQExpression)
    }

    @Test
    fun `test error handling for HTTP call failure`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val future = CompletableFuture<JsonNode>()
        future.completeExceptionally(RuntimeException("HTTP call failed"))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock headers and query params
        every { mockHeaders.getAdditionalProperties() } returns emptyMap()
        every { mockQuery.getAdditionalProperties() } returns emptyMap()

        // Mock HttpCall execution to return a failed future
        every {
            mockHttpCall.execute(
                method = any(),
                endpoint = any(),
                headers = any(),
                body = any(),
                query = any(),
                output = any(),
                redirect = any()
            )
        } returns future

        // Execute and verify
        assertFailsWith<RuntimeException> {
            callHttpInstance.execute()
        }
    }
}
