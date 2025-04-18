package com.lemline.core.nodes.activities

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.common.logger
import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.expressions.JQExpression
import com.lemline.core.json.LemlineJson
import com.lemline.core.json.LemlineJson.toJsonElement
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.UriTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val headers = httpArgs.headers?.getAdditionalProperties()?.mapValues { it.value.toString() } ?: emptyMap()

        // Extract body
        val body = if (httpArgs.body != null) {
            LemlineJson.jacksonMapper.convertValue(httpArgs.body, JsonNode::class.java)
        } else {
            null
        }

        // Extract query parameters
        val query = httpArgs.query?.getAdditionalProperties() ?: emptyMap()

        // Extract output format
        val output = httpArgs.output?.value() ?: "content"

        // Extract redirect flag
        val redirect = httpArgs.isRedirect

        // Execute HTTP call
        val future = httpCall.execute(
            method = method,
            endpoint = endpoint,
            headers = headers,
            body = body,
            query = query,
            output = output,
            redirect = redirect
        )

        // Get result and set as output
        try {
            // Execute the HTTP call and get the result
            val result = withContext(Dispatchers.IO) { future.get() }
            val jsonResult = result.toJsonElement()
            this.rawOutput = jsonResult
        } catch (e: java.util.concurrent.ExecutionException) {
            // Unwrap the cause of the ExecutionException
            // This is necessary because CompletableFuture.get() wraps exceptions in ExecutionException
            val cause = e.cause
            println("[DEBUG_LOG] HTTP call error: ${cause?.javaClass?.name}: ${cause?.message}")

            // Handle different types of exceptions according to the Serverless Workflow specification
            // See: https://serverlessworkflow.io/spec/1.0.0/errors/communication
            when (cause) {
                is RuntimeException -> {
                    // HTTP error status codes (4xx, 5xx)
                    // These are thrown by HttpCall when the response status is outside the 2xx range
                    if (cause.message?.startsWith("HTTP error:") == true) {
                        val statusCode =
                            cause.message?.substringAfter("HTTP error: ")?.substringBefore(",")?.toIntOrNull() ?: 500
                        error(
                            com.lemline.core.errors.WorkflowErrorType.COMMUNICATION,
                            "HTTP error: $statusCode",
                            cause.message,
                            statusCode
                        )
                    } else {
                        // Other runtime exceptions
                        error(
                            com.lemline.core.errors.WorkflowErrorType.COMMUNICATION,
                            cause.message,
                            cause.stackTraceToString()
                        )
                    }
                }

                else -> {
                    // Other exceptions
                    error(
                        com.lemline.core.errors.WorkflowErrorType.COMMUNICATION,
                        "HTTP call failed: ${cause?.message}",
                        cause?.stackTraceToString()
                    )
                }
            }
        } catch (e: Exception) {
            // Handle any other exceptions that might occur
            println("[DEBUG_LOG] Unexpected error: ${e.javaClass.name}: ${e.message}")
            error(
                com.lemline.core.errors.WorkflowErrorType.COMMUNICATION,
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
                    try {
                        val result = JQExpression.eval(transformedInput, expr, scope)
                        result.toString()
                    } catch (e: Exception) {
                        error(
                            com.lemline.core.errors.WorkflowErrorType.EXPRESSION,
                            e.message,
                            e.stackTraceToString()
                        )
                    }
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
