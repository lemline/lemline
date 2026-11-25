// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonNode
import com.lemline.common.json.LemlineJson.toJsonPrimitive
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.HTTPArguments
import java.net.URLEncoder

private const val URI_VARIABLE_SEGMENT = "(?:[A-Za-z0-9_]|%[A-Fa-f0-9]{2})+"
private val URI_TEMPLATE_VARIABLE = Regex("""\{($URI_VARIABLE_SEGMENT(?:\.$URI_VARIABLE_SEGMENT)*)}""")

/**
 * Node processor for CallHTTP (HTTP call task) - pure functional model.
 *
 * CallHTTP enables workflows to interact with external services over HTTP.
 * It's an activity task (leaf node) with no children.
 *
 * ## Example Workflow
 *
 * ```yaml
 * do:
 *   - getPost:
 *       call: http
 *       with:
 *         method: GET
 *         endpoint: https://jsonplaceholder.typicode.com/posts/1
 *   - createPost:
 *       call: http
 *       with:
 *         method: POST
 *         endpoint: https://api.example.com/posts
 *         headers:
 *           Content-Type: application/json
 *         body:
 *           title: "Test Post"
 *           body: "This is a test"
 *         output: response
 * ```
 *
 * ## HTTP Call Configuration
 *
 * The `with` field contains HTTP-specific arguments:
 * - **method**: HTTP verb (GET, POST, PUT, DELETE, PATCH, etc.)
 * - **endpoint**: Target URL or endpoint configuration with authentication
 * - **headers**: Optional HTTP headers
 * - **query**: Optional query parameters
 * - **body**: Optional request body (for POST/PUT)
 * - **output**: Response format (content, raw, response)
 * - **redirect**: Whether to follow redirects
 *
 * @property node Immutable CallHTTP definition
 */
class CallHttpProcessor(
    node: Node<CallHTTP>,
) : EndpointCallProcessor<CallHTTP>(node) {
    /**
     * Build the HTTP call configuration.
     *
     * Extracts and resolves all HTTP parameters from the task definition,
     * including endpoint URL, headers, body, and authentication.
     *
     * @param nodeStack The stack of nodes currently being processed
     * @param transformedInput Transformed input from parent
     * @param scope Expression evaluation scope
     * @return CallHttpStarted event with the resolved configuration
     */
    override fun startedEvent(
        nodeStack: NodeStack,
        transformedInput: JsonNode,
        scope: Scope,
    ): WorkflowEvent {
        logger.debug { "Building HTTP call config for task: ${node.name}" }

        val httpArgs = node.task.with

        // Extract method
        val method = httpArgs.method.uppercase()

        // Extract endpoint URL
        val endpoint = toUrl(httpArgs.endpoint, transformedInput, scope) { template ->
            expandUriTemplate(template, transformedInput)
        }

        // Extract and resolve authentication
        val authentication = resolveAuthentication(httpArgs.endpoint, transformedInput, scope)

        // Extract headers
        val headers = evaluateStringMap(
            httpArgs.headers?.getHTTPHeaders()?.additionalProperties,
            httpArgs.headers?.runtimeExpression, "headers", transformedInput, scope,
        )

        val query = evaluateStringMap(
            httpArgs.query?.getHTTPQuery()?.additionalProperties,
            httpArgs.query?.runtimeExpression, "query", transformedInput, scope,
        )

        // Evaluate body: runtime expressions are resolved at any depth
        val body: JsonNode? = httpArgs.body?.let {
            eval(transformedInput, with(LemlineJson) { it.toJsonNode() }, scope)
        }

        // Extract output format
        val output = httpArgs.output ?: HTTPArguments.HTTPOutput.CONTENT

        // Extract redirect setting
        val redirect = httpArgs.isRedirect

        val config = CallHttpConfig(
            method = method,
            url = endpoint,
            headers = headers,
            query = query,
            body = body,
            output = output,
            redirect = redirect,
            authentication = authentication
        )

        return CallHttpStarted(
            nodeStack = nodeStack,
            input = transformedInput,
            config = config
        )
    }

    private fun expandUriTemplate(template: String, input: JsonNode): String =
        URI_TEMPLATE_VARIABLE.replace(template) { placeholder ->
            val name = placeholder.groupValues[1]
            val value = input.get(name)
            when {
                value == null || value.isNull -> ""
                value.isTextual || value.isNumber || value.isBoolean -> encodeUriTemplateValue(value.content)
                else -> raiseError(EXPRESSION, "URI template variable '$name' must be a string, number, boolean, or null")
            }
        }

    private fun encodeUriTemplateValue(value: String): String =
        // RFC 6570 simple expansion preserves only unreserved characters.
        URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun evaluateStringMap(
        values: Map<String, Any?>?,
        expression: String?,
        name: String,
        input: JsonNode,
        scope: Scope,
    ): Map<String, String> {
        if (values == null && expression == null) return emptyMap()
        val resolved = eval(input, expression?.toJsonNode() ?: values.toJsonNode(), scope)
        if (resolved !is ObjectNode) raiseError(EXPRESSION, "HTTP $name must evaluate to an object")
        return resolved.mapValues { (_, value) -> value.toJsonPrimitive().content }
    }
}
