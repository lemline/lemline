package com.lemline.core.nodes.activities

import com.lemline.common.logger
import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.utils.toUrl
import io.serverlessworkflow.api.types.CallHTTP
import kotlinx.serialization.json.JsonElement

class CallHttpInstance(
    override val node: Node<CallHTTP>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallHTTP>(node, parent) {

    private val httpCall = HttpCall()
    private val logger = logger()

    override suspend fun execute() {
        logger.info("Executing HTTP call: ${node.name}")

        val callHttp = node.task
        val httpArgs = callHttp.with

        // Extract method
        val method = httpArgs.method

        // Extract endpoint URL
        val endpoint = toUrl(httpArgs.endpoint)

        // Extract headers
        val headers = LemlineJson.encodeToString(httpArgs.headers)

        // Extract body
        val body: JsonElement? = httpArgs.body?.let { with(LemlineJson) { it.toJsonElement() } }

        // Extract query parameters
        val query: Map<String, String> = LemlineJson.encodeToString(httpArgs.query)

        // Extract output format
        val output: String = httpArgs.output?.value() ?: "content"

        // Extract redirect flag
        val redirect = httpArgs.isRedirect

        // Execute the HTTP call and get the result
        try {
            // Execute the HTTP call directly using the suspendable function
            this.rawOutput = httpCall.execute(
                method = method,
                endpoint = endpoint,
                headers = headers,
                body = body,
                query = query,
                output = output,
                redirect = redirect
            )
        } catch (e: RuntimeException) {
            val statusCode = e.message
                ?.substringAfter("HTTP error: ")
                ?.substringBefore(",")
                ?.toIntOrNull()
            when (statusCode) {
                null -> error(
                    COMMUNICATION,
                    "Unexpected HTTP error: ${e.message}",
                    e.stackTraceToString()
                )

                in 300..399 -> error(
                    COMMUNICATION,
                    "Redirection error: $statusCode",
                    e.message,
                    statusCode
                )

                in 400..499 -> error(
                    COMMUNICATION,
                    "Client error: $statusCode",
                    e.message,
                    statusCode
                )

                in 500..599 -> error(
                    COMMUNICATION,
                    "Server error: $statusCode",
                    e.message,
                    statusCode
                )

                else -> error(
                    COMMUNICATION,
                    "Unexpected HTTP error: $statusCode",
                    e.message,
                    statusCode
                )
            }
        } catch (e: Exception) {
            // Handle any other exceptions that might occur
            error(
                COMMUNICATION,
                "HTTP call failed: ${e.message}",
                e.stackTraceToString()
            )
        }
    }
}
