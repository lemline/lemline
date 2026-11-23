// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.LemlineJson.toJsonNode
import com.lemline.common.json.buildJsonObject
import com.lemline.common.json.jsonValue
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.nodes.Node
import com.lemline.core.processors.scope.Scope
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowEvent.CallA2AStarted
import io.serverlessworkflow.api.types.A2AArguments.WithA2AMethod
import io.serverlessworkflow.api.types.CallA2A

data class CallA2AConfig(
    val method: WithA2AMethod,
    val url: String,
    val isCardResource: Boolean,
    val parameters: ObjectNode,
    val authentication: HttpAuthentication? = null,
)

class CallA2AProcessor(node: Node<CallA2A>) : EndpointCallProcessor<CallA2A>(node) {
    override fun startedEvent(nodeStack: NodeStack, transformedInput: JsonNode, scope: Scope): CallA2AStarted {
        val args = node.task.with
        val endpoint = args.agentCard?.endpoint ?: args.server
            ?: raiseError(CONFIGURATION, "A2A requires agentCard or server")
        val parameters = args.parameters?.let {
            val expression = it.string?.let(::jsonValue) ?: it.withA2AParameters.additionalProperties.toJsonNode()
            eval(transformedInput, expression, scope) as? ObjectNode
                ?: raiseError(EXPRESSION, "A2A parameters must evaluate to an object")
        }?.deepCopy() ?: buildJsonObject {}
        if (args.method == WithA2AMethod.MESSAGE_SEND || args.method == WithA2AMethod.MESSAGE_STREAM) {
            val message = when (val existing = parameters.get("message")) {
                null -> parameters.putObject("message")
                is ObjectNode -> existing
                else -> raiseError(CONFIGURATION, "A2A message must be an object")
            }
            if (!message.has("messageId")) message.put("messageId", nodeStack.deriveIdempotentId("-a2a-message").toString())
            if (!message.has("role")) message.put("role", "user")
        }
        return CallA2AStarted(nodeStack, transformedInput, CallA2AConfig(
            args.method, toUrl(endpoint, transformedInput, scope), args.agentCard != null,
            parameters, resolveAuthentication(endpoint, transformedInput, scope),
        ))
    }
}
