package com.lemline.runtime.sw.activities.calls

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.client.Client
import jakarta.ws.rs.client.ClientBuilder
import jakarta.ws.rs.client.Entity
import jakarta.ws.rs.client.Invocation.Builder
import jakarta.ws.rs.client.WebTarget
import jakarta.ws.rs.core.Response
import java.util.concurrent.CompletableFuture

class HttpCall {

    private val client: Client = ClientBuilder.newClient()

    fun execute(
        method: String,
        endpoint: String,
        headers: Map<String, String>,
        body: JsonNode?,
        query: Map<String, Any> = emptyMap(),
        output: String = "content",
        redirect: Boolean = false
    ): CompletableFuture<JsonNode> {
        return CompletableFuture.supplyAsync {
            val target: WebTarget = client.target(endpoint)
                .queryParam("redirect", redirect.toString())

            // Set query parameters
            query.forEach { (key, value) ->
                target.queryParam(key, value) // Reassign target after adding query param
            }

            val request: Builder = target.request()

            // Set headers
            headers.forEach { (key, value) -> request.header(key, value) }

            // Execute the request based on the HTTP method
            val response: Response = when (method.uppercase()) {
                HttpMethod.POST -> request.post(Entity.json(body))
                HttpMethod.GET -> request.get()
                HttpMethod.PUT -> request.put(Entity.json(body))
                HttpMethod.DELETE -> request.delete()
                else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
            }

            // Check for HTTP errors
            if (response.status < 200 || response.status >= 300) {
                throw RuntimeException("HTTP error: ${response.status}, ${response.readEntity(String::class.java)}")
            }

            // Handle output format based on DSL spec
            when (output.lowercase()) {
                "raw" -> {
                    // Base64 encode the response content
                    val bytes = response.readEntity(String::class.java).toByteArray()
                    val base64Content = java.util.Base64.getEncoder().encodeToString(bytes)
                    JsonNodeFactory.instance.textNode(base64Content) // Create a JsonNode with the base64 content
                }

                "content" -> {
                    // Return deserialized content directly
                    response.readEntity(JsonNode::class.java)
                }

                "response" -> {
                    // Return full response object
                    response.readEntity(JsonNode::class.java)
                }

                else -> throw IllegalArgumentException("Unsupported output format: $output. Must be one of: raw, content, response")
            }
        }
    }
} 