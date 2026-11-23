// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.json.*
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
import java.net.URI
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import org.a2aproject.sdk.client.http.A2AHttpResponse
import org.a2aproject.sdk.client.http.JdkA2AHttpClient

internal class A2AActivity(private val streamingClient: HttpClient) {
    private val transport = JdkA2AHttpClient()

    suspend fun execute(event: CallA2AStarted): JsonNode = try {
        call(event.config)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) {
        throw InternalException(InternalException.Error(
            type = "https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
            status = 500,
            position = event.nodePosition.toString(),
            title = "A2A ${event.config.method.value()} failed",
            details = e.message ?: e.javaClass.simpleName,
        ))
    }

    private suspend fun call(config: CallA2AConfig): JsonNode {
        val source = URI.create(config.url)
        val credentials = config.authentication?.authorizationHeader()
        val (cardUri, card) = discover(source, config.isCardResource, credentials)
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
            return streamingClient.preparePost(rpc.uri.toString()) {
                headers.forEach { (name, value) -> header(name, value) }
                // A healthy stream can outlive the ordinary request deadline. Keep the socket idle timeout.
                timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
                accept(ContentType.Text.EventStream)
                setBody(request)
            }.execute { response ->
                check(response.status.isSuccess()) { "HTTP ${response.status.value}: ${response.bodyAsText()}" }
                if (response.contentType()?.withoutParameters() != ContentType.Text.EventStream) {
                    // Agents may report JSON-RPC errors as ordinary JSON before opening the stream.
                    result(response.bodyAsText(), id)
                    error("Expected text/event-stream")
                }
                readStream(response.bodyAsChannel(), id)
            }
        }
        val response = transportCall {
            transport.createPost().url(rpc.uri.toString()).addHeaders(headers).body(request).post().checkedBody()
        }
        return result(response, id)
    }

    private suspend fun discover(source: URI, isCardResource: Boolean, credentials: String?): Pair<URI, JsonNode> {
        if (source.scheme?.lowercase() !in setOf("http", "https")) {
            require(isCardResource) { "A2A server must be an HTTP endpoint" }
            return source to runInterruptible(Dispatchers.IO) {
                DefaultResourceLoaderFactory.get().getResourceLoader(null, null).use { loader ->
                    loader.loadURI(source) { resource -> resource.open().bufferedReader().use { parseJson(it.readText()) } }
                }
            }
        }
        val card = if (isCardResource || source.path.endsWith("/.well-known/agent-card.json")) source else {
            val directory = source.rawPath.trimEnd('/') + "/.well-known/agent-card.json"
            val query = source.rawQuery?.let { "?$it" }.orEmpty()
            URI.create("${source.scheme}://${source.rawAuthority}$directory$query")
        }
        suspend fun fetch(uri: URI) = transportCall {
            val get = transport.createGet().url(uri.toString())
            credentials?.let { get.addHeader(HttpHeaders.Authorization, it) }
            get.get()
        }
        val response = fetch(card)
        // As in the Java resolver, a server may serve its card directly at its base URL.
        if (card != source && response.status() == 404) return source to parseJson(fetch(source).checkedBody())
        return card to parseJson(response.checkedBody())
    }

    private suspend fun <T : Any> transportCall(block: () -> T): T =
        withTimeoutOrNull(30.seconds) { runInterruptible(Dispatchers.IO) { block() } }
            ?: error("A2A HTTP request timed out")

    private fun A2AHttpResponse.checkedBody(): String {
        check(success()) { "HTTP ${status()}: ${body()}" }
        return body()
    }

    private fun result(data: String, id: String): JsonNode {
        val envelope = parseJson(data)
        check(envelope.path("jsonrpc").asText() == "2.0") { "Invalid JSON-RPC version" }
        envelope.get("error")?.let { error("JSON-RPC error: $it") }
        check(envelope.path("id").textValue() == id) { "Unexpected JSON-RPC response id" }
        return envelope.get("result") ?: error("Missing JSON-RPC result")
    }

    private suspend fun readStream(channel: ByteReadChannel, id: String): JsonNode {
        val results = LemlineJson.jacksonMapper.createArrayNode()
        val data = mutableListOf<String>()
        while (true) {
            val line = channel.readLine(LineEnding.Lenient) ?: break
            when {
                line.isEmpty() && data.isNotEmpty() -> {
                    results.add(result(data.joinToString("\n"), id))
                    data.clear()
                }
                line == "data" -> data.add("")
                line.startsWith("data:") -> data.add(line.substring(5).removePrefix(" "))
            }
        }
        channel.closedCause?.let { throw it }
        check(data.isEmpty() && results.lastOrNull()?.endsStream() == true) {
            "A2A stream ended before its final result"
        }
        return results
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
    val requirements = get("securityRequirements") ?: get("security")
        ?: return authorization.takeUnless { isCardResource }
    require(requirements.isArray) { "Invalid Agent Card security requirements" }
    if (requirements.isEmpty) return null
    for (requirement in requirements) {
        val schemes = requirement.get("schemes") ?: requirement
        require(schemes.isObject) { "Invalid Agent Card security requirement" }
        if (schemes.isEmpty) return null
        if (authorization != null && schemes.fieldNames().asSequence().all { name ->
                val definition = path("securitySchemes").path(name)
                val http = definition.get("httpAuthSecurityScheme") ?: definition.takeIf { it.path("type").asText() == "http" }
                http?.path("scheme")?.asText().equals(authorization.substringBefore(' '), true)
            }) return authorization
    }
    error("No configured credentials satisfy the Agent Card security requirements")
}
