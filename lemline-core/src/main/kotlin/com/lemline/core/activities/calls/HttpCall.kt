package com.lemline.core.activities.calls

import com.lemline.core.json.LemlineJson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

class HttpCall {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(LemlineJson.json) }
        // Configure timeout settings
        install(HttpTimeout) {
            requestTimeoutMillis = 30000 // 30 seconds
            connectTimeoutMillis = 15000 // 15 seconds
            socketTimeoutMillis = 30000 // 30 seconds
        }
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
     * @param redirect Whether to follow redirects
     * @return A JsonNode containing the response
     * @throws RuntimeException if the HTTP status code is not in the 2xx range
     * @throws IllegalArgumentException if the method or output format is not supported
     */
    suspend fun execute(
        method: String,
        endpoint: String,
        headers: Map<String, JsonPrimitive>,
        body: JsonElement?,
        query: Map<String, JsonPrimitive> = emptyMap(),
        output: String = "content",
        redirect: Boolean = false
    ): JsonElement {
        try {
            // Build the URL with query parameters
            val urlBuilder = URLBuilder(endpoint)

            // Add redirect parameter
            urlBuilder.parameters.append("redirect", redirect.toString())

            // Add query parameters
            query.forEach { (key, value) -> urlBuilder.parameters.append(key, value.content) }

            // Create the request
            val response: HttpResponse = client.request(urlBuilder.build()) {
                // Set the HTTP method
                this.method = when (method.uppercase()) {
                    "POST" -> HttpMethod.Post
                    "GET" -> HttpMethod.Get
                    "PUT" -> HttpMethod.Put
                    "DELETE" -> HttpMethod.Delete
                    else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
                }

                // Set headers
                headers.forEach { (key, value) -> header(key, value.content) }

                // Set the content type for requests with the body
                if (body != null && (method.uppercase() == "POST" || method.uppercase() == "PUT")) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

            // Check for HTTP errors
            if (!response.status.isSuccess()) {
                throw RuntimeException("HTTP error: ${response.status.value}, ${response.bodyAsText()}")
            }

            // Handle output format based on DSL spec
            return when (output.lowercase()) {
                "raw" -> {
                    // Base64 encode the response content
                    val bytes = response.bodyAsText().toByteArray()
                    val base64Content = java.util.Base64.getEncoder().encodeToString(bytes)
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

                else -> throw IllegalArgumentException("Unsupported output format: $output. Must be one of: raw, content, response")
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
}
