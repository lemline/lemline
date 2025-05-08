// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.calls

import com.lemline.core.errors.WorkflowErrorType.AUTHENTICATION
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.activities.CallHttpInstance
import com.lemline.core.utils.toSecret
import com.lemline.core.utils.toUrl
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.BearerAuthenticationPolicy
import io.serverlessworkflow.api.types.BearerAuthenticationProperties
import io.serverlessworkflow.api.types.DigestAuthenticationPolicy
import io.serverlessworkflow.api.types.DigestAuthenticationProperties
import io.serverlessworkflow.api.types.OAuth2AutenthicationData
import io.serverlessworkflow.api.types.OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.AUTHORIZATION_CODE
import io.serverlessworkflow.api.types.OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.CLIENT_CREDENTIALS
import io.serverlessworkflow.api.types.OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.PASSWORD
import io.serverlessworkflow.api.types.OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.REFRESH_TOKEN
import io.serverlessworkflow.api.types.OAuth2AutenthicationData.OAuth2AutenthicationDataGrant.URN_IETF_PARAMS_OAUTH_GRANT_TYPE_TOKEN_EXCHANGE
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient.ClientAuthentication.CLIENT_SECRET_BASIC
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient.ClientAuthentication.CLIENT_SECRET_JWT
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient.ClientAuthentication.CLIENT_SECRET_POST
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient.ClientAuthentication.NONE
import io.serverlessworkflow.api.types.OAuth2AutenthicationDataClient.ClientAuthentication.PRIVATE_KEY_JWT
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicy
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.OAuth2AuthenticationPropertiesEndpoints
import io.serverlessworkflow.api.types.OAuth2TokenDefinition
import io.serverlessworkflow.api.types.OpenIdConnectAuthenticationPolicy
import io.serverlessworkflow.api.types.SecretBasedAuthenticationPolicy
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * HttpCall is a utility class for making HTTP requests.
 * It supports GET, POST, PUT, and DELETE methods, with various options
 * for handling response formats and authentication.
 */
class HttpCall(private val nodeInstance: CallHttpInstance) {

    // Client configured to handle the HTTP requests
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(LemlineJson.json)
        }
        // Configure timeout settings
        install(HttpTimeout) {
            requestTimeoutMillis = 30000 // 30 seconds
            connectTimeoutMillis = 15000 // 15 seconds
            socketTimeoutMillis = 30000 // 30 seconds
        }
        // Install HttpRedirect plugin to allow redirect following
        install(HttpRedirect) {
            // Default configuration for the HttpRedirect plugin
            // The actual redirect behavior will be controlled by the `followRedirects` setting
            // in each request's client configuration
            checkHttpMethod = false // Allow redirects between different HTTP methods
            allowHttpsDowngrade = true // Allow redirects from HTTPS to HTTP if needed
        }
        // Disable the following redirect behavior by default - will be enabled per request if needed
        followRedirects = false
    }

    /**
     * Executes an HTTP request with the given parameters.
     *
     * This method is suspendable, which means it can be paused and resumed without blocking threads.
     * It also supports cancellation - if the coroutine is cancelled, the HTTP request will be cancelled as well.
     *
     * @param method The HTTP method to use (GET, POST, PUT, DELETE)
     * @param endpoint The URL to send the request to
     * @param headers HTTP headers to include in the request
     * @param body The request body (for POST and PUT requests)
     * @param query Query parameters to include in the URL
     * @param output The format of the output (raw, content, response)
     * @param redirect Specifies whether redirection status codes (300-399) should be treated as errors,
     *                 and whether HTTP redirects should be followed.
     *                 If set to false (default):
     *                 - HTTP redirects will not be followed
     *                 - An error will be raised for status codes outside the 200-299 range
     *                 If set to true:
     *                 - HTTP redirects will be automatically followed
     *                 - An error will be raised for status codes outside the 200-399 range
     * @param authentication The authentication configuration to use for the request, if any
     * @return A JsonNode containing the response
     * @throws RuntimeException if the HTTP status code is outside the acceptable range based on redirect parameter
     * @throws IllegalArgumentException if the method or output format is not supported
     */
    suspend fun execute(
        method: String,
        endpoint: String,
        headers: Map<String, String>,
        body: JsonElement?,
        query: Map<String, String> = emptyMap(),
        output: String = "content",
        redirect: Boolean = false,
        authentication: AuthenticationPolicy? = null,
    ): JsonElement {
        try {
            // Build the URL with query parameters
            val urlBuilder = URLBuilder(endpoint)

            // Add query parameters
            query.forEach { (key, value) -> urlBuilder.parameters.append(key, value) }

            // Create a new client configuration for this specific request with the redirect setting
            val response: HttpResponse = client.config {
                // Only follow redirects if explicitly requested
                followRedirects = redirect

                // Apply authentication if provided
                authentication?.let { applyAuthentication(it) }
            }.request(urlBuilder.build()) {
                // Set the HTTP method
                this.method = when (method.uppercase()) {
                    "POST" -> HttpMethod.Post
                    "GET" -> HttpMethod.Get
                    "PUT" -> HttpMethod.Put
                    "DELETE" -> HttpMethod.Delete
                    else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
                }

                // Set headers
                headers.forEach { (key, value) -> header(key, value) }

                // Set the content type for requests with the body
                if (body != null && (method.uppercase() == "POST" || method.uppercase() == "PUT")) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

            // Check for HTTP errors based on the redirect parameter
            if (!isAcceptableStatus(response.status, redirect)) {
                throw RuntimeException("HTTP error: ${response.status.value}, ${response.bodyAsText()}")
            }

            // Handle output format based on DSL spec
            return when (output.lowercase()) {
                "raw" -> {
                    // Base64 encode the response content
                    val bytes = response.bodyAsText().toByteArray()
                    val base64Content = Base64.getEncoder().encodeToString(bytes)
                    JsonPrimitive(base64Content) // Create a JsonNode with the base64 content
                }

                "content" -> {
                    // Return deserialized content directly
                    response.body<JsonElement>()
                }

                "response" -> {
                    // Return the full response object as JSON
                    response.body<JsonElement>()
                }

                else -> throw IllegalArgumentException(
                    "Unsupported output format: $output. Must be one of: raw, content, response",
                )
            }
        } catch (e: CancellationException) {
            // Propagate cancellation exceptions to allow proper coroutine cancellation
            throw e
        } catch (e: ClientRequestException) {
            // Handle HTTP error responses (4xx)
            throw RuntimeException("HTTP error: ${e.response.status.value}, ${e.response.bodyAsText()}")
        } catch (e: ServerResponseException) {
            // Handle HTTP error responses (5xx)
            throw RuntimeException("HTTP error: ${e.response.status.value}, ${e.response.bodyAsText()}")
        } catch (e: Exception) {
            // Handle other exceptions (connection errors, etc.)
            throw RuntimeException("HTTP call failed: ${e.message}", e)
        }
    }

    /**
     * Determines if a given HTTP status code is acceptable based on the redirect parameter.
     *
     * @param status The HTTP status code to check
     * @param redirect Whether to accept redirection status codes (300-399)
     * @return true if the status code is within the acceptable range, false otherwise
     */
    private fun isAcceptableStatus(status: HttpStatusCode, redirect: Boolean): Boolean {
        val statusValue = status.value
        return if (redirect) {
            // If redirect is true, accept 200-399 range
            statusValue in 200..399
        } else {
            // If redirect is false, only accept 200-299 range
            statusValue in 200..299
        }
    }

    /**
     * Applies authentication to the HTTP client configuration based on the authentication configuration.
     */
    private fun HttpClientConfig<*>.applyAuthentication(
        authentication: AuthenticationPolicy,
        fromUse: Boolean = false,
    ) = when (authentication) {
        is BasicAuthenticationPolicy -> when (val auth = authentication.basic.get()) {
            is BasicAuthenticationProperties -> applyBasicAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> nodeInstance.error(RUNTIME, "Unsupported basic authentication type: ${auth::class.simpleName}")
        }

        is BearerAuthenticationPolicy -> when (val auth = authentication.bearer.get()) {
            is BearerAuthenticationProperties -> applyBearerAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> nodeInstance.error(RUNTIME, "Unsupported bearer authentication type: ${auth::class.simpleName}")
        }

        is OAuth2AuthenticationPolicy -> when (val auth = authentication.oauth2.get()) {
            is OAuth2AuthenticationPolicyConfiguration -> applyOauth2Auth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> nodeInstance.error(RUNTIME, "Unsupported oauth2 authentication type: ${auth::class.simpleName}")
        }

        is DigestAuthenticationPolicy -> when (val auth = authentication.digest.get()) {
            is DigestAuthenticationProperties -> applyDigestAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> nodeInstance.error(RUNTIME, "Unsupported digest authentication type: ${auth::class.simpleName}")
        }

        is OpenIdConnectAuthenticationPolicy -> when (val auth = authentication.oidc.get()) {
            is OAuth2AutenthicationData -> applyOpenIdAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> nodeInstance.error(RUNTIME, "Unsupported openId authentication type: ${auth::class.simpleName}")
        }

        else -> nodeInstance.error(RUNTIME, "Unsupported authentication type: ${authentication::class.simpleName}")
    }

    /**
     * Applies secret-based authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applySecretBasedAuth(
        secretBasedAuthenticationPolicy: SecretBasedAuthenticationPolicy,
        fromUse: Boolean,
    ) {
        val name = secretBasedAuthenticationPolicy.use
        // Check if the authentication is circular
        if (fromUse) nodeInstance.error(CONFIGURATION, "Circular definition of the named authentification: $name")
        // Check if the authentication is defined in the workflow's use section
        nodeInstance.rootInstance.node.task.use?.authentications?.additionalProperties?.get(name)?.let {
            applyAuthentication(it.get(), true)
        } ?: nodeInstance.error(CONFIGURATION, "Named authentification not found: $name")
    }

    /**
     * Applies Digest authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applyDigestAuth(digestAuthenticationProperties: DigestAuthenticationProperties) {
        val username = digestAuthenticationProperties.username
            ?: nodeInstance.error(CONFIGURATION, "Username is missing for Digest authentication")
        val password = digestAuthenticationProperties.password?.let { nodeInstance.toSecret(it) }
            ?: nodeInstance.error(CONFIGURATION, "Password is missing for Digest authentication")

        install(Auth) {
            digest {
                credentials {
                    DigestAuthCredentials(
                        username = username,
                        password = password,
                    )
                }
            }
        }
    }

    /**
     * Applies Basic authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applyBasicAuth(basicAuthenticationProperties: BasicAuthenticationProperties) {
        val username = basicAuthenticationProperties.username
            ?: nodeInstance.error(CONFIGURATION, "Username is missing for Basic authentication")
        val password = basicAuthenticationProperties.password?.let { nodeInstance.toSecret(it) }
            ?: nodeInstance.error(CONFIGURATION, "Password is missing for Basic authentication")

        install(Auth) {
            basic {
                credentials {
                    BasicAuthCredentials(username = username, password = password)
                }
            }
        }
    }

    /**
     * Applies Bearer authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applyBearerAuth(bearerAuthenticationProperties: BearerAuthenticationProperties) {
        val token = bearerAuthenticationProperties.token?.let { nodeInstance.toSecret(it) }
            ?: nodeInstance.error(CONFIGURATION, "Token is missing for Bearer authentication")

        install(Auth) {
            bearer {
                loadTokens {
                    // Provide the token (optionally refresh if needed)
                    BearerTokens(accessToken = token, refreshToken = null)
                }
            }
        }
    }

    /**
     * Applies OAuth2 authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applyOauth2Auth(
        oauth2AuthenticationPolicyConfiguration: OAuth2AuthenticationPolicyConfiguration,
    ) {
        val authData = oauth2AuthenticationPolicyConfiguration.getoAuth2AutenthicationData()
        val endpoints = oauth2AuthenticationPolicyConfiguration.getoAuth2ConnectAuthenticationProperties().endpoints

        applyOauth2Auth(authData, endpoints)
    }

    private fun HttpClientConfig<*>.applyOauth2Auth(
        authData: OAuth2AutenthicationData,
        endpoints: OAuth2AuthenticationPropertiesEndpoints,
    ) {
        install(Auth) {
            bearer {
                loadTokens {
                    val tokenResponse = requestOAuth2TokenWithCache(client, authData, endpoints)
                    BearerTokens(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken ?: "",
                    )
                }
                refreshTokens {
                    val refreshData = authData.apply {
                        grant = REFRESH_TOKEN
                        subject = OAuth2TokenDefinition().apply { token = oldTokens?.refreshToken }
                    }
                    val tokenResponse = requestOAuth2Token(client, refreshData, endpoints)
                    BearerTokens(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken ?: "",
                    )
                }
            }
        }
    }

    private fun HttpClientConfig<*>.applyOpenIdAuth(authData: OAuth2AutenthicationData) {
        val endpoints = OAuth2AuthenticationPropertiesEndpoints() // default endpoints

        applyOauth2Auth(authData, endpoints)
    }

    @Serializable
    data class OAuthTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("token_type") val tokenType: String,
        @SerialName("expires_in") val expiresIn: Int,
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("id_token") val idToken: String? = null,
        val scope: String? = null,
    )

    private suspend fun requestOAuth2Token(
        client: HttpClient,
        authData: OAuth2AutenthicationData,
        endpoints: OAuth2AuthenticationPropertiesEndpoints,
    ): OAuthTokenResponse {
        val authority = authData.authority?.let { nodeInstance.toUrl(it) }
            ?: nodeInstance.error(CONFIGURATION, "Authority is missing for OAuth2 authentification")
        val tokenUrl = URLBuilder(authority).apply { path(endpoints.token) }.buildString()

        val params = Parameters.build {
            append("grant_type", authData.grant.value())

            val authMethod = authData.client?.authentication ?: CLIENT_SECRET_POST
            val authMethodError by lazy { "is required for ${authMethod.value()} of OAuth2 authentication" }

            val clientId = authData.client?.id
                ?: nodeInstance.error(CONFIGURATION, "client.id $authMethodError")
            val clientSecret = authData.client?.secret?.let { nodeInstance.toSecret(it) }

            when (authMethod) {
                CLIENT_SECRET_POST -> {
                    clientSecret ?: nodeInstance.error(CONFIGURATION, "client.secret $authMethodError")
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                }

                CLIENT_SECRET_BASIC -> {
                    clientSecret ?: nodeInstance.error(CONFIGURATION, "client.secret $authMethodError")
                    headers {
                        append(
                            HttpHeaders.Authorization,
                            "Basic " + Base64.getEncoder()
                                .encodeToString("$clientId:$clientSecret".toByteArray()),
                        )
                    }
                }

                CLIENT_SECRET_JWT, PRIVATE_KEY_JWT -> {
                    append("client_id", clientId)
                    append("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
                    val jwtAssertion = authData.client.assertion
                        ?: nodeInstance.error(CONFIGURATION, "client.assertion $authMethodError")
                    append("client_assertion", jwtAssertion)
                }

                NONE -> {
                    append("client_id", clientId)
                }
            }

            if (authData.scopes.isNotEmpty()) {
                append("scope", authData.scopes.joinToString(" "))
            }

            if (authData.audiences.isNotEmpty()) {
                append("audience", authData.audiences.joinToString(" "))
            }

            when (val grantType = authData.grant) {
                AUTHORIZATION_CODE -> {
                    nodeInstance.error(RUNTIME, "authorization_code grant is not yet supported") // TODO

//                    authData.request?.let { req ->
//                        req.code?.let { append("code", it) }
//                        req.redirectUri?.let { append("redirect_uri", it) }
//                        req.codeVerifier?.let { append("code_verifier", it) }
//                    }
                }

                CLIENT_CREDENTIALS -> {
                    /* No additional parameters needed */
                }

                PASSWORD -> {
                    val username = authData.username
                        ?: nodeInstance.error(CONFIGURATION, "username is missing for ${grantType.value()} grant")
                    val password = authData.password
                        ?: nodeInstance.error(CONFIGURATION, "password is missing for ${grantType.value()} grant")
                    append("username", username)
                    append("password", password)
                }

                REFRESH_TOKEN -> {
                    val refreshToken = authData.subject.token?.let { nodeInstance.toSecret(it) }
                        ?: nodeInstance.error(CONFIGURATION, "subject.token is missing for ${grantType.value()} grant")
                    append("refresh_token", refreshToken)
                }

                URN_IETF_PARAMS_OAUTH_GRANT_TYPE_TOKEN_EXCHANGE -> {
                    val subjectToken = authData.subject.token?.let { nodeInstance.toSecret(it) }
                        ?: nodeInstance.error(CONFIGURATION, "subject.token is missing for ${grantType.value()} grant")
                    val actorToken = authData.actor.token?.let { nodeInstance.toSecret(it) }
                        ?: nodeInstance.error(CONFIGURATION, "actor.token is missing for ${grantType.value()} grant")
                    append("subject_token", subjectToken)
                    append("actor_token", actorToken)
                    append("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                    append("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                }

                else -> nodeInstance.error(CONFIGURATION, "Unsupported grant type: $grantType")
            }
        }

        return client.submitForm(
            url = tokenUrl,
            formParameters = params,
        ).body()
    }

    /**
     * Cache for OAuth2 tokens to avoid unnecessary requests.
     * The cache uses a combination of authority, client ID, grant type, scopes, audiences, username, and subject token
     * as the key to uniquely identify each token.
     */
    private val tokenCache = ConcurrentHashMap<Int, CachedOAuthToken>()
    private val tokenLocks = ConcurrentHashMap<Int, Mutex>()

    data class CachedOAuthToken(
        val token: OAuthTokenResponse,
        val expirationTime: Long, // epoch millis
        val refreshable: Boolean,
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > expirationTime - 30_000 // 30 sec early
    }

    /**
     * Requests an OAuth2 token with caching support.
     * If a valid token is already cached, it will be returned instead of making a new request.
     * If the cached token is expired or not available, a new token will be requested and cached.
     */
    private suspend fun requestOAuth2TokenWithCache(
        client: HttpClient,
        authData: OAuth2AutenthicationData,
        endpoints: OAuth2AuthenticationPropertiesEndpoints,
    ): OAuthTokenResponse {
        val key = cacheKey(authData)
        val lock = tokenLocks.computeIfAbsent(key) { Mutex() }

        return lock.withLock {
            val cached = tokenCache[key]

            if (cached != null && !cached.isExpired()) {
                return@withLock cached.token
            }

            // Try refresh
            if (cached?.refreshable == true && cached.token.refreshToken != null) {
                val refreshData = authData.copyWith(
                    grant = REFRESH_TOKEN,
                    subject = OAuth2TokenDefinition().apply { token = cached.token.refreshToken },
                )

                try {
                    val refreshed = requestOAuth2Token(client, refreshData, endpoints)
                    tokenCache[key] = CachedOAuthToken(
                        token = refreshed,
                        expirationTime = System.currentTimeMillis() + (refreshed.expiresIn * 1000L),
                        refreshable = refreshed.refreshToken != null,
                    )
                    return@withLock refreshed
                } catch (e: Exception) {
                    tokenCache.remove(key)
                    nodeInstance.error(
                        AUTHENTICATION,
                        "Failed to refresh OAuth2 token: ${e.message}",
                        e.stackTraceToString(),
                    )
                }
            }

            // Full token request
            val newToken = requestOAuth2Token(client, authData, endpoints)
            tokenCache[key] = CachedOAuthToken(
                token = newToken,
                expirationTime = System.currentTimeMillis() + (newToken.expiresIn * 1000L),
                refreshable = newToken.refreshToken != null,
            )
            return@withLock newToken
        }
    }

    /**
     * Generates a cache key for the OAuth2 token based on the authentication data.
     * The key is a hash of the authority, client ID, grant type, scopes, audiences, username, and subject token.
     */
    private fun cacheKey(authData: OAuth2AutenthicationData): Int = Objects.hash(
        authData.authority,
        authData.client?.id,
        authData.grant,
        authData.scopes,
        authData.audiences,
        authData.username,
        authData.subject?.token,
    )

    private fun OAuth2AutenthicationData.copyWith(
        grant: OAuth2AutenthicationData.OAuth2AutenthicationDataGrant? = this.grant,
        subject: OAuth2TokenDefinition? = this.subject,
    ): OAuth2AutenthicationData {
        val copy = OAuth2AutenthicationData()

        copy.authority = this.authority
        copy.grant = grant
        copy.client = this.client
        copy.request = this.request
        copy.issuers = ArrayList(this.issuers)
        copy.scopes = ArrayList(this.scopes)
        copy.audiences = ArrayList(this.audiences)
        copy.username = this.username
        copy.password = this.password
        copy.subject = subject
        copy.actor = this.actor

        return copy
    }
}
