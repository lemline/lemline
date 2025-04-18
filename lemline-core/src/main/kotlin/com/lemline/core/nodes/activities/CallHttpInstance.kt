package com.lemline.core.nodes.activities

import com.lemline.common.logger
import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.errors.WorkflowErrorType.COMMUNICATION
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.UriTemplate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

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
        val endpoint = extractEndpointUrl(httpArgs.endpoint)

        // Extract headers
        val headers = LemlineJson.encodeToPrimitive(httpArgs.headers)

        // Extract body
        val body: JsonElement? = httpArgs.body?.let { with(LemlineJson) { it.toJsonElement() } }

        // Extract query parameters
        val query: Map<String, JsonPrimitive> = LemlineJson.encodeToPrimitive(httpArgs.query)

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

    private fun extractEndpointUrl(endpoint: Endpoint): String {
        return when (val value = endpoint.get()) {
            is UriTemplate -> {
                when (val templateValue = value.get()) {
                    is URI -> templateValue.toString()
                    is String -> templateValue
                    else -> error(
                        com.lemline.core.errors.WorkflowErrorType.EXPRESSION,
                        "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}"
                    )
                }
            }

            is EndpointConfiguration -> {
                val uri = value.uri
                when (val uriValue = uri.get()) {
                    is UriTemplate -> {
                        when (val templateValue = uriValue.get()) {
                            is URI -> templateValue.toString()
                            is String -> templateValue
                            else -> error(
                                com.lemline.core.errors.WorkflowErrorType.EXPRESSION,
                                "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}"
                            )
                        }
                    }

                    is String -> uriValue
                    else -> error(
                        com.lemline.core.errors.WorkflowErrorType.EXPRESSION,
                        "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}"
                    )
                }
            }

            is String -> {
                if (value.matches(Regex("^\\s*\\$\\{.+}\\s*$"))) {
                    // This is a runtime expression, evaluate it
                    val expr = value.trim().substring(2, value.length - 1)
                    evalString(transformedInput, expr, "EndPoint")
                } else {
                    value
                }
            }

            else -> error(
                com.lemline.core.errors.WorkflowErrorType.EXPRESSION,
                "Unsupported Endpoint type: ${value?.javaClass?.name}"
            )
        }
    }
}
