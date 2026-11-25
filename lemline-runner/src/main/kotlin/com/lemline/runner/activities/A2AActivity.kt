// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
import com.lemline.core.errors.CommunicationException
import com.lemline.core.errors.InternalException
import com.lemline.core.processors.CallA2AConfig
import com.lemline.core.states.WorkflowEvent.CallA2AStarted
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.serverlessworkflow.api.types.A2AArguments.WithA2AMethod
import io.serverlessworkflow.impl.resources.DefaultResourceLoaderFactory
import java.io.IOException
import java.net.URI
import java.nio.channels.UnresolvedAddressException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.a2aproject.sdk.client.http.A2AHttpResponse
import org.a2aproject.sdk.client.http.JdkA2AHttpClient

/**
 * Limits for A2A calls. Agents commonly take longer than an ordinary HTTP call,
 * so these do not reuse the 30 second deadline of the shared HTTP client.
 */
internal data class A2ALimits(
    /** Deadline of one non-streaming request (card download or JSON-RPC call). */
    val requestTimeout: Duration = 5.minutes,
    /** Longest silence tolerated on an open stream. */
    val streamIdleTimeout: Duration = 5.minutes,
    /** Longest total duration of one stream. */
    val streamTimeout: Duration = 30.minutes,
    /** Largest amount of event data accepted from one stream. */
    val maxStreamChars: Long = 16L * 1024 * 1024,
)

internal class A2AActivity(
    private val streamingClient: HttpClient,
    private val limits: A2ALimits = A2ALimits(),
) {
    private val transport = JdkA2AHttpClient()

    suspend fun execute(event: CallA2AStarted): JsonNode = try {
        call(event.config)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        // The agent answered a failing HTTP status, or gave no usable answer at all:
        // the workflow can catch these apart, and by status, to retry them.
        val failure = when (e) {
            is CommunicationException -> e
            is IOException, is UnresolvedAddressException -> CommunicationException(
                "Unable to reach ${event.config.url.server()}: ${e.message ?: e.javaClass.simpleName}", cause = e,
            )
            else -> null
        }
        throw InternalException(InternalException.Error(
            type = "https://serverlessworkflow.io/spec/1.0.0/errors/${if (failure == null) "runtime" else "communication"}",
            status = failure?.status ?: 500,
            position = event.nodePosition.toString(),
            title = "A2A ${event.config.method.value()} failed",
            detail = (failure ?: e).message ?: e.javaClass.simpleName,
        ))
    }

    // The server only: the path and the query of the URL may carry credentials.
    private fun String.server() = runCatching { URI.create(this) }.getOrNull()
        ?.takeIf { it.host != null }?.let { "${it.scheme}://${it.host}${if (it.port >= 0) ":${it.port}" else ""}" } ?: "the agent"

    private suspend fun call(config: CallA2AConfig): JsonNode {
        val source = URI.create(config.url)
        val credentials = config.authentication?.authorizationHeader()
        val (cardUri, card) = discover(source, config.isCardResource, credentials)
            // Without a card, `server` is the JSON-RPC endpoint itself.
            ?: (source to buildJsonObject { put("url", source.toString()) })
        val rpc = A2ARpcEndpoint.from(card)
        val headers = buildMap {
            put(HttpHeaders.ContentType, "application/json")
            if (rpc.v1) put("A2A-Version", "1.0")
            val scopedCredentials = credentials.takeIf { cardUri.sameOrigin(rpc.uri) }
            val authorization = card.rpcAuthorization(scopedCredentials, config.isCardResource)
            authorization?.let { put(HttpHeaders.Authorization, it) }
        }
        val id = UUID.randomUUID().toString()
        val method = config.method
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", rpc.methodName(method))
            put("params", rpc.parameters(config))
        }.toString()
        if (method == WithA2AMethod.MESSAGE_STREAM || method == WithA2AMethod.TASKS_RESUBSCRIBE) {
            return withTimeoutOrNull(limits.streamTimeout) {
                streamingClient.preparePost(rpc.uri.toString()) {
                    headers.forEach { (name, value) -> header(name, value) }
                    // A healthy stream can outlive the ordinary request deadline: it is bounded
                    // by its own total duration and by the longest silence between two events.
                    timeout {
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                        socketTimeoutMillis = limits.streamIdleTimeout.inWholeMilliseconds
                    }
                    accept(ContentType.Text.EventStream)
                    setBody(request)
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        throw CommunicationException("HTTP ${response.status.value}: ${response.bodyAsText()}", response.status.value)
                    }
                    if (response.contentType()?.withoutParameters() != ContentType.Text.EventStream) {
                        // Agents may report JSON-RPC errors as ordinary JSON before opening the stream.
                        result(response.bodyAsText(), id)
                        error("Expected text/event-stream")
                    }
                    readStream(response.bodyAsChannel(), id)
                }
            } ?: throw CommunicationException("A2A stream exceeded ${limits.streamTimeout}")
        }
        val response = transportCall {
            transport.createPost().url(rpc.uri.toString()).addHeaders(headers).body(request).post().checkedBody()
        }
        return result(response, id)
    }

    /** Returns the Agent Card and where it was found, or null when `server` publishes none. */
    private suspend fun discover(source: URI, isCardResource: Boolean, credentials: String?): Pair<URI, JsonNode>? {
        if (source.scheme?.lowercase() !in setOf("http", "https")) {
            require(isCardResource) { "A2A server must be an HTTP endpoint" }
            return source to runInterruptible(Dispatchers.IO) {
                DefaultResourceLoaderFactory.get().getResourceLoader(null, null).use { loader ->
                    loader.loadURI(source) { resource -> resource.open().bufferedReader().use { parseJson(it.readText()) } }
                }
            }
        }
        suspend fun fetch(uri: URI) = transportCall {
            val get = transport.createGet().url(uri.toString())
            credentials?.let { get.addHeader(HttpHeaders.Authorization, it) }
            get.get()
        }
        if (isCardResource || source.path.endsWith(WELL_KNOWN_CARD)) {
            return source to parseJson(fetch(source).checkedBody())
        }
        // The card is looked up below the server URL, as the Java resolver does, then at
        // the root of its host, where RFC 8615 places well-known resources.
        val query = source.rawQuery?.let { "?$it" }.orEmpty()
        val origin = "${source.scheme}://${source.rawAuthority}"
        val candidates = listOf(source.rawPath.trimEnd('/'), "").distinct()
            .map { URI.create("$origin$it$WELL_KNOWN_CARD$query") }
        for (candidate in candidates) {
            val response = fetch(candidate)
            if (response.status() != 404) return candidate to parseJson(response.checkedBody())
        }
        // A server may serve its card directly at its base URL. Anything else answering
        // there is the JSON-RPC endpoint the workflow asked for.
        val response = fetch(source)
        val card = response.takeIf { it.success() }?.let { runCatching { parseJson(it.body()) }.getOrNull() }
        return card?.takeIf { it.has("url") || it.has("supportedInterfaces") }?.let { source to it }
    }

    private suspend fun <T : Any> transportCall(block: () -> T): T =
        withTimeoutOrNull(limits.requestTimeout) { runInterruptible(Dispatchers.IO) { block() } }
            ?: throw CommunicationException("A2A HTTP request timed out after ${limits.requestTimeout}")

    private fun A2AHttpResponse.checkedBody(): String {
        if (!success()) throw CommunicationException("HTTP ${status()}: ${body()}", status())
        return body()
    }

    private fun result(data: String, id: String): JsonNode {
        val envelope = parseJson(data)
        check(envelope.path("jsonrpc").asText() == "2.0") { "Invalid JSON-RPC version" }
        // Some servers serialize both members and leave the unused one null.
        envelope.get("error")?.takeUnless { it.isNull }?.let { error("JSON-RPC error: $it") }
        check(envelope.path("id").textValue() == id) { "Unexpected JSON-RPC response id" }
        return envelope.get("result") ?: error("Missing JSON-RPC result")
    }

    private suspend fun readStream(channel: ByteReadChannel, id: String): JsonNode {
        val results = LemlineJson.jacksonMapper.createArrayNode()
        val data = mutableListOf<String>()
        var received = 0L
        while (true) {
            val line = channel.readLine(LineEnding.Lenient) ?: break
            when {
                line.isEmpty() && data.isNotEmpty() -> {
                    val event = result(data.joinToString("\n"), id)
                    results.add(event)
                    data.clear()
                    // The final event completes the call, whether or not the agent then closes the connection.
                    if (event.endsStream()) return results
                }
                line == "data" -> data.add("")
                line.startsWith("data:") -> {
                    received += line.length
                    check(received <= limits.maxStreamChars) { "A2A stream exceeded ${limits.maxStreamChars} characters" }
                    data.add(line.substring(5).removePrefix(" "))
                }
            }
        }
        channel.closedCause?.let { throw it }
        throw CommunicationException("A2A stream ended before its final result")
    }

    private fun JsonNode.endsStream(): Boolean {
        if (has("message") || path("kind").asText() == "message") return true
        get("final")?.let { return it.isBoolean && it.booleanValue() }
        val update = get("statusUpdate") ?: get("task") ?: this
        val state = update.path("status").path("state").asText().removePrefix("TASK_STATE_").lowercase().replace('_', '-')
        return state in setOf("completed", "failed", "canceled", "rejected", "input-required", "auth-required")
    }
}

// Endpoint credentials may satisfy a matching requirement at the same origin. A card cannot
// authorize forwarding the credentials used to download it to another host.
private fun URI.sameOrigin(other: URI): Boolean {
    fun URI.effectivePort() = if (port >= 0) port else if (scheme.equals("https", true)) 443 else 80
    return scheme.equals(other.scheme, true) && host.equals(other.host, true) && effectivePort() == other.effectivePort()
}

private fun JsonNode.rpcAuthorization(authorization: String?, isCardResource: Boolean): String? {
    val requirements = (get("securityRequirements") ?: get("security"))?.takeUnless { it.isNull }
        ?: return authorization.takeUnless { isCardResource }
    require(requirements.isArray) { "Invalid Agent Card security requirements" }
    if (requirements.isEmpty) return null
    // The requirements are alternatives. Configured credentials that satisfy one are sent
    // even when the card also allows anonymous calls: skills may require them.
    var anonymous = false
    for (requirement in requirements) {
        val schemes = requirement.get("schemes") ?: requirement
        require(schemes.isObject) { "Invalid Agent Card security requirement" }
        if (schemes.isEmpty) {
            anonymous = true
        } else if (authorization != null && schemes.fieldNames().asSequence().all { name ->
                path("securitySchemes").path(name).accepts(authorization)
            }) return authorization
    }
    if (anonymous) return null
    error("No configured credentials satisfy the Agent Card security requirements")
}

// A security scheme in either card format: A2A 1.0 wraps it, earlier cards tag it with `type`.
private fun JsonNode.accepts(authorization: String): Boolean {
    val scheme = authorization.substringBefore(' ')
    val type = path("type").asText()
    val http = get("httpAuthSecurityScheme") ?: takeIf { type == "http" }
    if (http != null) return http.path("scheme").asText().equals(scheme, true)
    // OAuth 2 and OpenID Connect access tokens are presented as bearer tokens.
    val token = has("oauth2SecurityScheme") || has("openIdConnectSecurityScheme") || type in setOf("oauth2", "openIdConnect")
    return token && scheme.equals("Bearer", true)
}

private const val WELL_KNOWN_CARD = "/.well-known/agent-card.json"
