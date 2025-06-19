// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.calls

import com.lemline.core.OnError
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.json.LemlineJson
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.BearerAuthenticationPolicy
import io.serverlessworkflow.api.types.BearerAuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.BearerAuthenticationProperties
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import io.serverlessworkflow.api.types.OAuth2AutenthicationData
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicy
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.OAuth2AuthenticationPropertiesEndpoints
import io.serverlessworkflow.api.types.OAuth2ConnectAuthenticationProperties
import io.serverlessworkflow.api.types.Oauth2
import io.serverlessworkflow.api.types.UriTemplate
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class HttpCallTest {

    private fun createHttpCallWithMockEngine(
        getSecretByName: (String) -> String = { it },
        getAuthenticationPolicyByName: (String) -> AuthenticationPolicy = { error("Not implemented") },
        onErrorImpl: OnError = { type, message, details, code ->
            throw RuntimeException("onError called: $type, $message, $details, $code")
        },
        handler: MockRequestHandler,
    ): HttpCall {
        val mockEngine = MockEngine(handler)
        val mockClient = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(LemlineJson.json) }
        }

        val httpCall = HttpCall(getSecretByName, getAuthenticationPolicyByName, onErrorImpl)
        val clientField = HttpCall::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(httpCall, mockClient)

        return httpCall
    }

    @Test
    fun `test GET request with successful response`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val httpCall = createHttpCallWithMockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("https://example.com/api", request.url.toString())
            assertEquals("application/json", request.headers["Content-Type"])
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api"),
            headers = mapOf("Content-Type" to "application/json"),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive("success"), (result as JsonObject)["result"])
    }

    @Test
    fun `test POST request with body and successful response`() = runTest {
        val requestBody = JsonObject(mapOf("name" to JsonPrimitive("test")))
        val jsonResponse = JsonObject(mapOf("id" to JsonPrimitive(123)))
        val httpCall = createHttpCallWithMockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://example.com/api/create", request.url.toString())
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Post,
            url = Url("https://example.com/api/create"),
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive(123), (result as JsonObject)["id"])
    }

    @Test
    fun `test PUT request with body and successful response`() = runTest {
        val requestBody = JsonObject(mapOf("name" to JsonPrimitive("updated")))
        val jsonResponse = JsonObject(mapOf("updated" to JsonPrimitive(true)))
        val httpCall = createHttpCallWithMockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("https://example.com/api/update/123", request.url.toString())
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Put,
            url = Url("https://example.com/api/update/123"),
            headers = mapOf("Content-Type" to "application/json"),
            body = requestBody,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive(true), (result as JsonObject)["updated"])
    }

    @Test
    fun `test DELETE request with successful response`() = runTest {
        val jsonResponse = buildJsonObject { put("deleted", true) }
        val httpCall = createHttpCallWithMockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("https://example.com/api/delete/123", request.url.toString())
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Delete,
            url = Url("https://example.com/api/delete/123"),
            headers = mapOf("Content-Type" to "application/json"),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive(true), (result as JsonObject)["deleted"])
    }

    @Test
    fun `test with query parameters`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val httpCall = createHttpCallWithMockEngine() { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("test", request.url.parameters["q"])
            assertEquals("1", request.url.parameters["page"])
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/search?q=test&page=1"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive("success"), (result as JsonObject)["result"])
    }

    @Test
    fun `test with raw output format`() = runTest {
        val responseText = """{"result":"success"}"""
        val base64Response = Base64.getEncoder().encodeToString(responseText.toByteArray())
        val httpCall = createHttpCallWithMockEngine() { _ ->
            respond(
                content = responseText,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.RAW,
            redirect = false,
            authentication = null,
        )
        assertEquals(JsonPrimitive(base64Response), result)
    }

    @Test
    fun `test with response output format`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val httpCall = createHttpCallWithMockEngine() { request ->
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.RESPONSE,
            redirect = false,
            authentication = null,
        ) as JsonObject

        val expected = buildJsonObject {
            // Include status code and headers in the response
            put("request", buildJsonObject {
                put("method", JsonPrimitive("GET"))
                put("uri", JsonPrimitive("https://example.com/api"))
                put("headers", buildJsonObject {
                    put("Accept", JsonArray(listOf(JsonPrimitive("application/json"))))
                    put("Accept-Charset", JsonArray(listOf(JsonPrimitive("UTF-8"))))
                })
            })
            put("statusCode", JsonPrimitive(200))
            put("headers", buildJsonObject {
                put("Content-Type", JsonArray(listOf(JsonPrimitive("application/json"))))
            })
            put("content", buildJsonObject {
                put("result", JsonPrimitive("success"))
            })
        }
        assertEquals(expected, result)
    }

    @Test
    fun `test with HTTP error response`() = runTest {
        val httpCall = createHttpCallWithMockEngine() { request ->
            respond(
                content = "Not Found",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = HttpMethod.Get,
                url = Url("https://example.com/api/nonexistent"),
                headers = emptyMap(),
                body = null,
                output = HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
        assert(exception.message?.contains("404") == true)
    }

    @Test
    fun `test with redirect parameter false and redirection status code`() = runTest {
        val httpCall = createHttpCallWithMockEngine() { _ ->
            respond(
                content = "",
                status = HttpStatusCode.Found,
                headers = headersOf(HttpHeaders.Location, "https://example.com/new-location"),
            )
        }
        val exception = assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = HttpMethod.Get,
                url = Url("https://example.com/api/redirect"),
                headers = emptyMap(),
                body = null,
                output = HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
        assert(exception.message?.contains("302") == true)
    }

    @Test
    fun `test actual redirect following with redirect parameter true`() = runTest {
        val finalJsonResponse = buildJsonObject { put("result", "redirected successfully") }
        var requestCount = 0
        val httpCall = createHttpCallWithMockEngine() { request ->
            requestCount++
            if (requestCount == 1) {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(
                        HttpHeaders.Location,
                        "https://example.com/redirected",
                    ),
                )
            } else {
                assertEquals("https://example.com/redirected", request.url.toString())
                respond(
                    content = finalJsonResponse.toString(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/original"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = true,
            authentication = null,
        ) as JsonObject
        assertEquals(2, requestCount)
        assertEquals(JsonPrimitive("redirected successfully"), result["result"])
    }

    @Test
    fun `test with basic authentication`() = runTest {
        val credentials = "testuser:testpass"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
        val expectedAuthHeader = "Basic $encodedCredentials"
        var receivedAuthHeader: String? = null
        val httpCall = createHttpCallWithMockEngine() { request ->
            receivedAuthHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = buildJsonObject { put("result", "success") }.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = mapOf("Authorization" to expectedAuthHeader),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        ) as JsonObject
        assertEquals(expectedAuthHeader, receivedAuthHeader, "Authorization header was not passed correctly")
        assertEquals(JsonPrimitive("success"), result["result"], "Response content incorrect")
    }

    @Test
    fun `test with basic authentication policy`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val httpCall = createHttpCallWithMockEngine() { request ->
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val basicAuthProperties = BasicAuthenticationProperties().apply {
            username = "testuser"
            password = "testpass"
        }
        val basicAuthConfig = BasicAuthenticationPolicyConfiguration().apply {
            basicAuthenticationProperties = basicAuthProperties
        }
        val basicAuth = BasicAuthenticationPolicy().apply {
            basic = basicAuthConfig
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = basicAuth,
        ) as JsonObject
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test that manual basic auth header is correctly passed`() = runTest {
        val credentials = "testuser:testpass"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray(Charsets.UTF_8))
        val expectedAuthHeader = "Basic $encodedCredentials"
        var receivedAuthHeader: String? = null
        val httpCall = createHttpCallWithMockEngine() { request ->
            receivedAuthHeader = request.headers[HttpHeaders.Authorization]
            respond(
                content = buildJsonObject { put("result", "success") }.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = mapOf("Authorization" to expectedAuthHeader),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = null,
        ) as JsonObject
        assertEquals(expectedAuthHeader, receivedAuthHeader, "Authorization header was not passed correctly")
        assertEquals(JsonPrimitive("success"), result["result"], "Response content incorrect")
    }

    @Test
    fun `test with bearer token authentication`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val bearerAuthProperties = BearerAuthenticationProperties().apply {
            token = "my-jwt-token"
        }
        val bearerAuth = BearerAuthenticationPolicy().apply {
            bearer = BearerAuthenticationPolicyConfiguration().apply {
                bearerAuthenticationProperties = bearerAuthProperties
            }
        }
        val httpCall = createHttpCallWithMockEngine() { request ->
            val authHeader = request.headers[HttpHeaders.Authorization]
            assertEquals("Bearer my-jwt-token", authHeader)
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = bearerAuth,
        ) as JsonObject
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test with oauth2 authentication token`() = runTest {
        val jsonResponse = buildJsonObject { put("result", "success") }
        val bearerAuthProperties = BearerAuthenticationProperties().apply {
            token = "oauth2-access-token"
        }
        val oauth2AuthAsBearer = BearerAuthenticationPolicy().apply {
            bearer = BearerAuthenticationPolicyConfiguration().apply {
                bearerAuthenticationProperties = bearerAuthProperties
            }
        }
        val httpCall = createHttpCallWithMockEngine() { request ->
            val authHeader = request.headers[HttpHeaders.Authorization]
            assertEquals("Bearer oauth2-access-token", authHeader)
            respond(
                content = jsonResponse.toString(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = oauth2AuthAsBearer,
        ) as JsonObject
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test with oauth2 authentication without token should throw exception`() = runTest {
        val oauth2Data = OAuth2AutenthicationData().apply {
            authority = UriTemplate().apply { literalUriTemplate = "https://example.com/oauth2" }
            grant = OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.CLIENT_CREDENTIALS
            client = OAuth2AutenthicationDataClient().apply {
                id = "clientId"
                secret = "clientSecret"
                authentication = OAuth2AutenthicationDataClient.ClientAuthentication.CLIENT_SECRET_POST
            }
        }
        val oauthProperties = OAuth2ConnectAuthenticationProperties().apply {
            endpoints = OAuth2AuthenticationPropertiesEndpoints()
        }
        val oauth2Config = OAuth2AuthenticationPolicyConfiguration().apply {
            setoAuth2AutenthicationData(oauth2Data)
            setoAuth2ConnectAuthenticationProperties(oauthProperties)
        }
        val oauth2 = Oauth2().apply { setoAuth2ConnectAuthenticationProperties(oauth2Config) }
        val oauth2AuthPolicy = OAuth2AuthenticationPolicy().apply { setOauth2(oauth2) }
        val mockEngine = MockEngine { request ->
            if (request.url.toString().contains("/oauth2")) {
                respond(
                    content = """
                        {
                            "access_token": "mocked-access-token",
                            "token_type": "bearer",
                            "expires_in": 3600
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respond(
                    content = "{\"result\": \"success\"}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }
        val client = HttpClient(mockEngine) {
            install(ContentNegotiation) { json(LemlineJson.json) }
        }
        val httpCall = createHttpCallWithMockEngine(handler = { error("should not be called") })
        val clientField = HttpCall::class.java.getDeclaredField("client")
        clientField.isAccessible = true
        clientField.set(httpCall, client)
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/api/secure"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = false,
            authentication = oauth2AuthPolicy,
        ) as JsonObject
        assertEquals(JsonPrimitive("success"), result["result"])
    }

    @Test
    fun `test HTTP error handling`() = runTest {
        val onErrorCalled = AtomicReference<Array<Any?>>()
        val httpCall = createHttpCallWithMockEngine(
            onErrorImpl = { type, message, details, code ->
                onErrorCalled.set(arrayOf(type, message, details, code))
                throw RuntimeException(message)
            },
        ) {
            respond(
                content = "Internal Server Error",
                status = HttpStatusCode.InternalServerError,
            )
        }
        assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = HttpMethod.Get,
                url = Url("https://example.com/api/error"),
                headers = emptyMap(),
                body = null,
                output = HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
        val (type, message, details, code) = onErrorCalled.get()
        assertEquals(WorkflowErrorType.COMMUNICATION, type)
        assertEquals("Server error: 500", message)
        assertEquals("Internal Server Error", details)
        assertEquals(500, code)
    }

    @Test
    fun `test redirect handling without redirect flag`() = runTest {
        val onErrorCalled = AtomicReference<Array<Any?>>()
        val httpCall = createHttpCallWithMockEngine(
            onErrorImpl = { type, message, details, code ->
                onErrorCalled.set(arrayOf(type, message, details, code))
                throw RuntimeException(message)
            },
        ) {
            respond(
                content = "",
                status = HttpStatusCode.MovedPermanently,
                headers = headersOf(HttpHeaders.Location, "https://new-location.com"),
            )
        }
        assertFailsWith<RuntimeException> {
            httpCall.execute(
                method = HttpMethod.Get,
                url = Url("https://example.com/redirect"),
                headers = emptyMap(),
                body = null,
                output = HTTPOutput.CONTENT,
                redirect = false,
                authentication = null,
            )
        }
        val (type, message, details, code) = onErrorCalled.get()
        assertEquals(WorkflowErrorType.COMMUNICATION, type)
        assertEquals("Redirection error: 301", message)
        assertEquals("", details)
        assertEquals(301, code)
    }

    @Test
    fun `test redirect handling with redirect flag`() = runTest {
        val onErrorCalled = AtomicReference<Array<Any?>>()
        val httpCall = createHttpCallWithMockEngine(
            onErrorImpl = { type, message, details, code ->
                onErrorCalled.set(arrayOf(type, message, details, code))
                throw RuntimeException("onError should not be called")
            },
        ) { request ->
            if (request.url.toString() == "https://example.com/redirect") {
                respond(
                    content = "",
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "https://example.com/final"),
                )
            } else {
                respond(
                    content = """{"result":"redirected"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        }
        val result = httpCall.execute(
            method = HttpMethod.Get,
            url = Url("https://example.com/redirect"),
            headers = emptyMap(),
            body = null,
            output = HTTPOutput.CONTENT,
            redirect = true,
            authentication = null,
        )
        assertNull(onErrorCalled.get(), "onError should not have been called")
        assertEquals(JsonPrimitive("redirected"), (result as JsonObject)["result"])
    }
}
