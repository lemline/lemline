// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ValueNode
import com.lemline.common.json.*
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.CallState
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.AuthenticationPolicyReference
import io.serverlessworkflow.api.types.AuthenticationPolicyUnion
import io.serverlessworkflow.api.types.BasicAuthenticationPolicy
import io.serverlessworkflow.api.types.BasicAuthenticationProperties
import io.serverlessworkflow.api.types.BearerAuthenticationPolicy
import io.serverlessworkflow.api.types.BearerAuthenticationProperties
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicy
import io.serverlessworkflow.api.types.OAuth2AuthenticationPolicyConfiguration
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI
import kotlin.time.Instant

abstract class EndpointCallProcessor<T : TaskBase>(node: Node<T>) : AbstractProcessor<T, CallState>(node) {
    override val isAsync = true

    override fun stateWhenEnteringFromParent(transformedInput: JsonNode, scope: Scope, startedAt: Instant) = CallState(startedAt = startedAt)

    /**
     * Resolves authentication policy to HttpAuthentication.
     * Returns null if no authentication is configured.
     */
    protected fun resolveAuthentication(endpointUnion: Endpoint, input: JsonNode, scope: Scope): HttpAuthentication? {
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
    protected fun toUrl(
        endpointUnion: Endpoint,
        data: JsonNode,
        scope: Scope,
        expandLiteralTemplate: (String) -> String = { it },
    ): String {
        return when (val endpoint = endpointUnion.get()) {
            // EndpointConfiguration object
            is EndpointConfiguration -> when (val uriValue = endpoint.uri.get()) {
                is UriTemplate -> uriTemplateToUrl(uriValue, expandLiteralTemplate)
                is String -> evaluateUrlExpression(uriValue, data, scope)
                else -> raiseError(RUNTIME, "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}")
            }
            // UriTemplate
            is UriTemplate -> uriTemplateToUrl(endpoint, expandLiteralTemplate)
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
        expandLiteralTemplate: (String) -> String,
    ): String = when (val templateValue = uriTemplate.get()) {
        is URI -> templateValue.toString()
        is String -> expandLiteralTemplate(templateValue)
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
            when (val result = eval(data, jsonValue(expression), scope)) {
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
