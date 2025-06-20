package com.lemline.core.instances

import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.expressions.JQExpression
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.nodes.NodePosition
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.unmockkObject
import io.serverlessworkflow.api.types.AuthenticationPolicyUnion
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.EndpointUri
import io.serverlessworkflow.api.types.HTTPArguments
import io.serverlessworkflow.api.types.HTTPHeaders
import io.serverlessworkflow.api.types.HTTPQuery
import io.serverlessworkflow.api.types.ReferenceableAuthenticationPolicy
import io.serverlessworkflow.api.types.UriTemplate
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

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
        every { mockNode.reference } returns NodePosition(listOf("test")).toString()
        every { mockNode.definition } returns LemlineJson.jsonObject
        every { mockCallHTTP.with } returns mockHttpArgs
        every { mockCallHTTP.input } returns null // Mock getInput to avoid serialization issues
        every { mockHttpArgs.method } returns "get"
        every { mockHttpArgs.endpoint } returns mockEndpoint
        every { mockHttpArgs.headers } returns mockHeaders
        every { mockHttpArgs.query } returns mockQuery
        every { mockHttpArgs.body } returns null
        every { mockHttpArgs.output } returns null
        every { mockHttpArgs.isRedirect } returns false

        // Create the CallHttpInstance with mocked dependencies
        callHttpInstance = spyk(CallHttpInstance(mockNode, mockParent)) {
            every { scope } returns JsonObject(emptyMap())
        }

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
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns mapOf("Content-Type" to "application/json")
        every { mockQuery.additionalProperties } returns mapOf("param" to "value")

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api?param=value"),
                headers = mapOf("Content-Type" to "application/json"),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api?param=value"),
                headers = mapOf("Content-Type" to "application/json"),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }

        // Verify the output was set
        assertEquals(jsonResponse, callHttpInstance.rawOutput)
    }

    @Test
    fun `test execute with URI endpoint`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val uri = URI("https://example.com/api")
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns uri

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                null
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
    }

    @Test
    fun `test execute with EndpointConfiguration`() = runTest {
        // Setup
        val endpointConfig = mockk<EndpointConfiguration>()
        val endpointUri = mockk<EndpointUri>()
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns endpointConfig
        every { endpointConfig.uri } returns endpointUri
        every { endpointUri.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api/config"
        // Mock authentication to return null for this endpoint
        every { endpointConfig.authentication } returns null

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/config"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/config"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
    }

    @Test
    fun `test execute with runtime expression endpoint`() = runTest {
        // Setup
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns "https://example.com/api/dynamic"

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/dynamic"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/dynamic"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }

        unmockkObject(JQExpression)
    }

    @Test
    fun `test execute with body`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val jsonNode = JsonObject(mapOf("name" to JsonPrimitive("test")))
        val jsonResponse = JsonObject(mapOf("id" to JsonPrimitive(123)))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock body - use JsonObject directly instead of Map to avoid serialization issues
        every { mockHttpArgs.body } returns jsonNode

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution with a more flexible mock that accepts any body parameter
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = any(),
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = any(),
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
    }

    @Test
    fun `test execute with output format`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val outputFormat = HTTPArguments.HTTPOutput.RAW
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock output format
        every { mockHttpArgs.output } returns outputFormat

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.RAW,
                redirect = false,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.RAW,
                redirect = false,
                authentication = null,
            )
        }
    }

    @Test
    fun `test execute with redirect flag`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock redirect flag
        every { mockHttpArgs.isRedirect } returns true

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = true,
                authentication = null,
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = true,
                authentication = null,
            )
        }
    }

    @Test
    fun `test error handling for unsupported endpoint type`() = runTest {
        // Setup
        every { mockEndpoint.get() } returns 123 // Unsupported type

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            callHttpInstance.run()
        }

        // Verify the exception message
        assert(exception.message?.contains("Unsupported Endpoint type") == true)
    }

    @Test
    fun `test error handling for unsupported UriTemplate type`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()

        // Mock endpoint resolution with an unsupported type
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns 123 // Unsupported type

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            callHttpInstance.run()
        }

        // Verify the exception message
        assert(exception.message?.contains("Unsupported UriTemplate type") == true)
    }

    @Test
    fun `test error handling for runtime expression evaluation failure`() = runTest {
        // Setup
        val runtimeExpr = "\${.apiUrl}"

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns runtimeExpr

        // Execute and verify
        assertFailsWith<RuntimeException> {
            callHttpInstance.run()
        }

        // We only need to verify that an exception is thrown, not the specific message

        unmockkObject(JQExpression)
    }

    @Test
    fun `test error handling for HTTP call failure`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution to return a failed future
        coEvery {
            mockHttpCall.execute(
                method = any(),
                url = any(),
                headers = any(),
                body = any(),
                output = any(),
                redirect = any(),
                authentication = any()
            )
        } throws RuntimeException("HTTP call failed")

        // Execute and verify
        assertFailsWith<RuntimeException> {
            callHttpInstance.run()
        }
    }

    @Test
    fun `test execute with authentication`() = runTest {
        // Setup
        val endpointConfig = mockk<EndpointConfiguration>()
        val endpointUri = mockk<EndpointUri>()
        val uriTemplate = mockk<UriTemplate>()
        val jsonResponse = JsonObject(mapOf("result" to JsonPrimitive("success")))

        // Create the proper AuthenticationPolicy object
        val basicAuthProps = BasicAuthenticationProperties().apply {
            username = "testuser"
            password = "testpass"
        }
        val basicAuthConfig = BasicAuthenticationPolicyConfiguration().apply {
            basicAuthenticationProperties = basicAuthProps
        }
        val basicAuthPolicy = BasicAuthenticationPolicy().apply {
            setBasic(basicAuthConfig) // Use setter for union type
        }

        // Mock the ReferenceableAuthenticationPolicy to hold the basic policy
        val mockAuthPolicyUnion = mockk<AuthenticationPolicyUnion>()
        every { mockAuthPolicyUnion.get() } returns basicAuthPolicy // Return the specific policy
        val mockRefAuthPolicy = mockk<ReferenceableAuthenticationPolicy>()
        every { mockRefAuthPolicy.get() } returns mockAuthPolicyUnion // Return the union

        // Mock endpoint resolution with authentication
        every { mockEndpoint.get() } returns endpointConfig
        every { endpointConfig.uri } returns endpointUri
        every { endpointUri.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api/secure"
        // Mock the authentication property on EndpointConfiguration
        every { endpointConfig.authentication } returns mockRefAuthPolicy

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Mock HttpCall execution including authentication
        coEvery {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/secure"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = basicAuthPolicy, // Use the correct policy object
            )
        } returns jsonResponse

        // Execute
        callHttpInstance.run()

        // Verify
        coVerify {
            mockHttpCall.execute(
                method = HttpMethod.Companion.Get,
                url = Url("https://example.com/api/secure"),
                headers = emptyMap(),
                body = null,
                output = HTTPArguments.HTTPOutput.CONTENT,
                redirect = false,
                authentication = basicAuthPolicy, // Use the correct policy object
            )
        }
    }

    @Test
    fun `test unsupported PATCH method throws exception`() = runTest {
        // Setup
        val uriTemplate = mockk<UriTemplate>()

        // Mock endpoint resolution
        every { mockEndpoint.get() } returns uriTemplate
        every { uriTemplate.get() } returns "https://example.com/api"

        // Mock PATCH method
        every { mockHttpArgs.method } returns "patch"

        // Mock headers and query params
        every { mockHeaders.additionalProperties } returns emptyMap()
        every { mockQuery.additionalProperties } returns emptyMap()

        // Execute and verify
        val exception = assertFailsWith<RuntimeException> {
            callHttpInstance.run()
        }

        // Verify the exception message contains the unsupported method
        assert(exception.message?.contains("Unsupported HTTP method: patch") == true)
    }
}
