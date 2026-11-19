// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.json.LemlineJson.toJsonNode
import com.lemline.common.json.LemlineJson.toJsonPrimitive
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.CallState
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.AuthenticationPolicyReference
import io.serverlessworkflow.api.types.AuthenticationPolicyUnion
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.BearerAuthenticationPolicy
import io.serverlessworkflow.api.types.BearerAuthenticationProperties
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.HTTPArguments
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicy
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import kotlin.time.Instant

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
) : AbstractProcessor<CallHTTP, CallState>(node) {

    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonNode, scope: Scope, startedAt: Instant) = CallState(startedAt = startedAt)

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
        val endpoint = toUrl(httpArgs.endpoint, transformedInput, scope)

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

    /**
     * Resolves authentication policy to HttpAuthentication.
     * Returns null if no authentication is configured.
     */
    private fun resolveAuthentication(endpointUnion: Endpoint, input: JsonNode, scope: Scope): HttpAuthentication? {
        val authPolicy = toAuthenticationPolicy(endpointUnion) ?: return null

        return when (authPolicy) {
            is BasicAuthenticationPolicy -> {
                when (val props = authPolicy.basic.get()) {
                    is BasicAuthenticationProperties -> {
                        val username = props.username
                            ?: raiseError(CONFIGURATION, "Basic authentication requires username")
                        val password = props.password
                            ?: raiseError(CONFIGURATION, "Basic authentication requires password")
                        HttpAuthentication.Basic(
                            evaluateAuthValue(username, "basic.username", input, scope),
                            evaluateAuthValue(password, "basic.password", input, scope),
                        )
                    }

                    else -> raiseError(RUNTIME, "Unsupported basic authentication: ${props?.javaClass?.name}")
                }
            }

            is BearerAuthenticationPolicy -> {
                when (val props = authPolicy.bearer.get()) {
                    is BearerAuthenticationProperties -> {
                        val token = props.token
                            ?: raiseError(CONFIGURATION, "Bearer authentication requires token")
                        HttpAuthentication.Bearer(evaluateAuthValue(token, "bearer.token", input, scope))
                    }

                    else -> raiseError(RUNTIME, "Unsupported bearer authentication: ${props?.javaClass?.name}")
                }
            }

            is OAuth2AuthenticationPolicy -> {
                when (val props = authPolicy.oauth2.get()) {
                    is OAuth2AuthenticationPolicyConfiguration -> {
                        // OAuth2 requires token resolution which is complex
                        // For now, we only support pre-resolved tokens
                        raiseError(CONFIGURATION, "OAuth2 authentication requires pre-resolved token")
                    }

                    else -> raiseError(RUNTIME, "Unsupported OAuth2 authentication: ${props?.javaClass?.name}")
                }
            }

            else -> raiseError(RUNTIME, "Unsupported authentication type: ${authPolicy.javaClass.name}")
        }
    }

    /**
     * Evaluates an authentication property, which may be a runtime expression (e.g. `${ $secrets.TOKEN }`).
     */
    private fun evaluateAuthValue(value: String, name: String, input: JsonNode, scope: Scope): String =
        when (val result = eval(input, jsonValue(value), scope)) {
            is NullNode -> raiseError(EXPRESSION, "Authentication $name must evaluate to a string, but got null")
            is ValueNode -> result.content
            else -> raiseError(EXPRESSION, "Authentication $name must evaluate to a string")
        }

    /**
     * Converts endpoint to URL string.
     * Handles both simple string URLs and endpoint configurations.
     */
    private fun toUrl(
        endpointUnion: Endpoint,
        data: JsonNode,
        scope: Scope
    ): String {
        return when (val endpoint = endpointUnion.get()) {
            // EndpointConfiguration object
            is EndpointConfiguration -> when (val uriValue = endpoint.uri.get()) {
                is UriTemplate -> uriTemplateToUrl(uriValue, data, scope)
                is String -> evaluateUrlExpression(uriValue, data, scope)
                else -> raiseError(RUNTIME, "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}")
            }
            // UriTemplate
            is UriTemplate -> uriTemplateToUrl(endpoint, data, scope)
            // Expression string
            is String -> evaluateUrlExpression(endpoint, data, scope)
            // Unsupported type
            else -> raiseError(RUNTIME, "Unsupported Endpoint type: ${endpoint?.javaClass?.name}")
        }
    }

    /**
     * Converts UriTemplate to URL string.
     */
    private fun uriTemplateToUrl(
        uriTemplate: UriTemplate,
        data: JsonNode,
        scope: Scope
    ): String = when (val templateValue = uriTemplate.get()) {
        is URI -> templateValue.toString()
        is String -> evaluateUrlExpression(templateValue, data, scope)
        else -> raiseError(RUNTIME, "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}")
    }

    /**
     * Evaluates URL expression if it contains JQ syntax.
     */
    private fun evaluateUrlExpression(
        expression: String,
        data: JsonNode,
        scope: Scope
    ): String = when (ExpressionUtils.isExpr(expression)) {
        true -> {
            val trimmed = ExpressionUtils.trimExpr(expression)
            when (val result = eval(data, jsonValue(trimmed), scope, false)) {
                is ValueNode -> result.content
                else -> raiseError(RUNTIME, "URL expression must evaluate to a string, but got: $result")
            }
        }

        false -> expression
    }

    /**
     * Extracts authentication policy from endpoint configuration.
     */
    private fun toAuthenticationPolicy(endpointUnion: Endpoint): AuthenticationPolicy? {
        val authPolicyUnion = when (val endpoint = endpointUnion.get()) {
            // EndpointConfiguration object
            is EndpointConfiguration -> endpoint.authentication
            // UriTemplate - no auth
            is UriTemplate -> null
            // Expression string - no auth
            is String -> null
            // Unsupported type
            else -> raiseError(RUNTIME, "Unsupported Endpoint type: ${endpoint?.javaClass?.name}")
        }

        return when (val authPolicy = authPolicyUnion?.get()) {
            null -> null
            is AuthenticationPolicyReference -> getAuthenticationPolicyByName(authPolicy.use)
            is AuthenticationPolicyUnion -> authPolicy.get()
            else -> raiseError(RUNTIME, "Unsupported AuthenticationPolicy type: ${authPolicy.javaClass.name}")
        }
    }

    /**
     * Retrieves authentication policy by name from workflow's use section.
     */
    private fun getAuthenticationPolicyByName(name: String): AuthenticationPolicy {
        return use?.authentications?.additionalProperties?.get(name)?.get()
            ?: raiseError(CONFIGURATION, "Named authentication not found: $name")
    }
}
