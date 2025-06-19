// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.calls

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.lemline.core.OnError
import com.lemline.core.errors.WorkflowErrorType.AUTHENTICATION
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.json.LemlineJson
import com.lemline.core.json.LemlineJson.toJsonElement
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
import io.ktor.util.*
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.BearerAuthenticationPolicy
import io.serverlessworkflow.api.types.BearerAuthenticationProperties
import io.serverlessworkflow.api.types.DigestAuthenticationPolicy
import io.serverlessworkflow.api.types.DigestAuthenticationProperties
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import nl.adaptivity.xmlutil.serialization.XML

// Yaml mapper for kotlin serialization
private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

// XML mapper for kotlin serialization
private val xml = XML { autoPolymorphic = true }

/**
 * HttpCall is a utility class for making HTTP requests.
 * It supports GET, POST, PUT, and DELETE methods, with various options
 * for handling response formats and authentication.
 */
class HttpCall(
    private val getSecretByName: (String) -> String,
    private val getAuthenticationPolicyByName: (String) -> AuthenticationPolicy,
    private val onError: OnError,
) {

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
     * Executes an HTTP request with the specified parameters.
     *
     * @param method The HTTP method to use (GET, POST, PUT, DELETE).
     * @param url The URL to send the request to.
     * @param headers A map of headers to include in the request.
     * @param body The body of the request, if applicable (for POST/PUT).
     * @param output The desired output format for the response.
     * @param redirect Whether to follow redirects (default is false).
     * @param authentication Optional authentication policy to apply to the request.
     * @return The response as a JsonElement based on the requested output format.
     */
    suspend fun execute(
        method: HttpMethod,
        url: Url,
        headers: Map<String, String>,
        body: JsonElement?,
        output: HTTPOutput,
        redirect: Boolean,
        authentication: AuthenticationPolicy?,
    ): JsonElement {

        // Create a new client configuration for this specific request with the redirect setting
        val response: HttpResponse = try {
            client.config {
                // Only follow redirects if explicitly requested
                followRedirects = redirect

                // Apply authentication if provided
                authentication?.let { applyAuthentication(it) }
            }.request(url) {
                // Set the HTTP method
                this.method = method
                // Set headers
                headers.forEach { (key, value) -> header(key, value) }
                // Set the content type for requests with the body
                if (body != null && (method == HttpMethod.Post || method == HttpMethod.Put)) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: Exception) {
            // Handle other exceptions (connection errors, etc.)
            onError(COMMUNICATION, "HTTP call failed: ${e.message}", e.stackTraceToString(), null)
        }

        // Check for HTTP errors based on the redirect parameter
        if (!isAcceptableStatus(response.status, redirect)) {
            val statusCode = response.status.value
            val responseBody = response.bodyAsText()
            val message = when (statusCode) {
                in 300..399 -> "Redirection error: $statusCode"
                in 400..499 -> "Client error: $statusCode"
                in 500..599 -> "Server error: $statusCode"
                else -> "Unexpected HTTP error: $statusCode"
            }
            onError(COMMUNICATION, message, responseBody, statusCode)
        }

        // Return response as requested
        return response.getAs(output)
    }

    private suspend fun HttpResponse.getAs(format: HTTPOutput): JsonElement = when (format) {
        HTTPOutput.RAW -> getRawContentAsJsonElement(this)
        HTTPOutput.CONTENT -> getContentAsJsonElement(this)
        HTTPOutput.RESPONSE -> getResponseAsJsonElement(this)
    }

    /**
     * Retrieves the HTTP response as a JsonElement, including request details, status code, headers, and content.
     *
     * @param response The HTTP response to process
     * @return A JsonElement containing the response details
     */
    private suspend fun getResponseAsJsonElement(response: HttpResponse) = buildJsonObject {
        // Include status code and headers in the response
        put("request", buildJsonObject {
            put("method", JsonPrimitive(response.request.method.value.uppercase()))
            put("uri", JsonPrimitive(response.request.url.toString()))
            put("headers", response.request.headers.toMap().toJsonElement())
        })
        put("statusCode", JsonPrimitive(response.status.value))
        put("headers", response.headers.toMap().toJsonElement())
        put("content", getContentAsJsonElement(response))
    }

    /**
     * Retrieves the raw content of the HTTP response as a base64-encoded JsonPrimitive.
     */
    private suspend fun getRawContentAsJsonElement(response: HttpResponse) =
        JsonPrimitive(Base64.getEncoder().encodeToString(response.bodyAsBytes()))

    /**
     * Retrieves the content of the HTTP response as a JsonElement based on the content type.
     * It supports JSON, YAML, XML, and plain text formats.
     *
     * @param response The HTTP response to process
     * @return A JsonElement representing the content of the response
     */
    private suspend fun getContentAsJsonElement(response: HttpResponse): JsonElement {
        val contentType = response.contentType()?.withoutParameters()?.toString()?.lowercase()
            ?: return getRawContentAsJsonElement(response)

        val text = response.bodyAsText()

        return try {
            when {
                contentType.contains("json") -> LemlineJson.json.parseToJsonElement(text)
                contentType.contains("yaml") -> yaml.decodeFromString(text)
                contentType.contains("xml") -> xml.decodeFromString(text)
                contentType.contains("text") -> JsonPrimitive(text)
                else -> getRawContentAsJsonElement(response)
            }
        } catch (e: Exception) {
            onError(COMMUNICATION, "Failed to parse response content as $contentType: ${e.message}", text, null)
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
            else -> onError(RUNTIME, "Unsupported basic authentication type: ${auth::class.simpleName}", null, null)
        }

        is BearerAuthenticationPolicy -> when (val auth = authentication.bearer.get()) {
            is BearerAuthenticationProperties -> applyBearerAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> onError(RUNTIME, "Unsupported bearer authentication type: ${auth::class.simpleName}", null, null)
        }

        is OAuth2AuthenticationPolicy -> when (val auth = authentication.oauth2.get()) {
            is OAuth2AuthenticationPolicyConfiguration -> applyOauth2Auth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> onError(RUNTIME, "Unsupported oauth2 authentication type: ${auth::class.simpleName}", null, null)
        }

        is DigestAuthenticationPolicy -> when (val auth = authentication.digest.get()) {
            is DigestAuthenticationProperties -> applyDigestAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> onError(RUNTIME, "Unsupported digest authentication type: ${auth::class.simpleName}", null, null)
        }

        is OpenIdConnectAuthenticationPolicy -> when (val auth = authentication.oidc.get()) {
            is OAuth2AutenthicationData -> applyOpenIdAuth(auth)
            is SecretBasedAuthenticationPolicy -> applySecretBasedAuth(auth, fromUse)
            else -> onError(RUNTIME, "Unsupported openId authentication type: ${auth::class.simpleName}", null, null)
        }

        else -> onError(RUNTIME, "Unsupported authentication type: ${authentication::class.simpleName}", null, null)
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
        if (fromUse) onError(CONFIGURATION, "Circular definition of the named authentification: $name", null, null)
        // Check if the authentication is defined in the workflow's use section
        applyAuthentication(getAuthenticationPolicyByName(name), true)
    }

    /**
     * Applies Digest authentication to the HTTP client configuration.
     */
    private fun HttpClientConfig<*>.applyDigestAuth(digestAuthenticationProperties: DigestAuthenticationProperties) {
        val username = digestAuthenticationProperties.username
            ?: onError(CONFIGURATION, "Username is missing for Digest authentication", null, null)
        val password = digestAuthenticationProperties.password?.let { getSecretByName(it) }
            ?: onError(CONFIGURATION, "Password is missing for Digest authentication", null, null)

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
            ?: onError(CONFIGURATION, "Username is missing for Basic authentication", null, null)
        val password = basicAuthenticationProperties.password?.let { getSecretByName(it) }
            ?: onError(CONFIGURATION, "Password is missing for Basic authentication", null, null)

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
        val token = bearerAuthenticationProperties.token?.let { getSecretByName(it) }
            ?: onError(CONFIGURATION, "Token is missing for Bearer authentication", null, null)

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
        val authority = authData.authority?.toUrl(onError)
            ?: onError(CONFIGURATION, "Authority is missing for OAuth2 authentification", null, null)
        val tokenUrl = URLBuilder(authority).apply { encodedPath += endpoints.token }.buildString()

        val params = Parameters.build {
            append("grant_type", authData.grant.value())

            val authMethod = authData.client?.authentication ?: CLIENT_SECRET_POST
            val authMethodError by lazy { "is required for ${authMethod.value()} of OAuth2 authentication" }

            val clientId = authData.client?.id
                ?: onError(CONFIGURATION, "client.id $authMethodError", null, null)
            val clientSecret = authData.client?.secret?.let { getSecretByName(it) }

            when (authMethod) {
                CLIENT_SECRET_POST -> {
                    clientSecret ?: onError(CONFIGURATION, "client.secret $authMethodError", null, null)
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                }

                CLIENT_SECRET_BASIC -> {
                    clientSecret ?: onError(CONFIGURATION, "client.secret $authMethodError", null, null)
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
                        ?: onError(CONFIGURATION, "client.assertion $authMethodError", null, null)
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
                    onError(RUNTIME, "authorization_code grant is not yet supported", null, null) // TODO

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
                        ?: onError(CONFIGURATION, "username is missing for ${grantType.value()} grant", null, null)
                    val password = authData.password
                        ?: onError(CONFIGURATION, "password is missing for ${grantType.value()} grant", null, null)
                    append("username", username)
                    append("password", password)
                }

                REFRESH_TOKEN -> {
                    val refreshToken = authData.subject.token?.let { getSecretByName(it) }
                        ?: onError(CONFIGURATION, "subject.token is missing for ${grantType.value()} grant", null, null)
                    append("refresh_token", refreshToken)
                }

                URN_IETF_PARAMS_OAUTH_GRANT_TYPE_TOKEN_EXCHANGE -> {
                    val subjectToken = authData.subject.token?.let { getSecretByName(it) }
                        ?: onError(CONFIGURATION, "subject.token is missing for ${grantType.value()} grant", null, null)
                    val actorToken = authData.actor.token?.let { getSecretByName(it) }
                        ?: onError(CONFIGURATION, "actor.token is missing for ${grantType.value()} grant", null, null)
                    append("subject_token", subjectToken)
                    append("actor_token", actorToken)
                    append("requested_token_type", "urn:ietf:params:oauth:token-type:access_token")
                    append("subject_token_type", "urn:ietf:params:oauth:token-type:access_token")
                }

                else -> onError(CONFIGURATION, "Unsupported grant type: $grantType", null, null)
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
                    onError(
                        AUTHENTICATION,
                        "Failed to refresh OAuth2 token: ${e.message}",
                        e.stackTraceToString(),
                        null,
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
