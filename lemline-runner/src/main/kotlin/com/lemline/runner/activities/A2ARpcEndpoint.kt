// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.core.processors.CallA2AConfig
import io.serverlessworkflow.api.types.A2AArguments.WithA2AMethod
import java.net.URI
import org.a2aproject.sdk.spec.A2AMethods

internal data class A2ARpcEndpoint(val uri: URI, val v1: Boolean, val tenant: String?) {
    companion object {
        fun from(card: JsonNode): A2ARpcEndpoint {
            // A2A 1.0 advertises versioned interfaces. Earlier cards have a primary URL
            // and may advertise other transports under additionalInterfaces.
            val interfaces = card.get("supportedInterfaces")?.takeIf { it.isArray }
            val jsonRpc = interfaces?.filter { it.path("protocolBinding").asText() == "JSONRPC" }.orEmpty()
            val endpoint = jsonRpc.firstOrNull { it.path("protocolVersion").asText().startsWith("1.") }
            // A 1.0 card may still offer the earlier protocol, in its list or as its primary URL.
            val legacy = jsonRpc.firstOrNull { it.path("protocolVersion").asText().startsWith("0.") }
                ?: if (card.path("preferredTransport").asText("JSONRPC") == "JSONRPC") card
                else card.path("additionalInterfaces").firstOrNull { it.path("transport").asText() == "JSONRPC" }
            val url = (endpoint ?: legacy)?.get("url")?.textValue() ?: error(
                if (interfaces != null) "Agent Card has no supported JSON-RPC interface" else "Missing Agent Card JSON-RPC URL"
            )
            val uri = URI.create(url)
            require(uri.host != null && uri.scheme.lowercase() in setOf("http", "https")) { "Invalid Agent Card RPC URL" }
            return A2ARpcEndpoint(uri, endpoint != null, endpoint?.get("tenant")?.textValue())
        }
    }

    fun methodName(method: WithA2AMethod) = if (v1) method.v1Name() else method.value()

    fun parameters(config: CallA2AConfig): ObjectNode {
        val sendsMessage = config.method == WithA2AMethod.MESSAGE_SEND || config.method == WithA2AMethod.MESSAGE_STREAM
        if (!v1) {
            if (!sendsMessage) return config.parameters
            // A2A 0.3 requires the discriminator on a Message. A2A 1.0 has none.
            return config.parameters.deepCopy().apply {
                (get("message") as? ObjectNode)?.takeIf { !it.has("kind") }?.put("kind", "message")
            }
        }
        return config.parameters.deepCopy().apply {
            if (tenant != null && !has("tenant")) put("tenant", tenant)
            if (sendsMessage) {
                // A2A 1.0 replaced `blocking` with its opposite and renamed the push configuration.
                (get("configuration") as? ObjectNode)?.let { configuration ->
                    configuration.remove("blocking")?.takeIf { it.isBoolean && !configuration.has("returnImmediately") }
                        ?.let { configuration.put("returnImmediately", !it.booleanValue()) }
                    configuration.remove("pushNotificationConfig")?.takeIf { !configuration.has("taskPushNotificationConfig") }
                        ?.let { configuration.set<JsonNode>("taskPushNotificationConfig", it) }
                }
                val message = get("message") as ObjectNode
                when (message.path("role").textValue()) {
                    "user" -> message.put("role", "ROLE_USER")
                    "agent" -> message.put("role", "ROLE_AGENT")
                }
                message.remove("kind")
                message.path("parts").filterIsInstance<ObjectNode>().forEach { part ->
                    part.remove("kind")
                    (part.remove("file") as? ObjectNode)?.let { file ->
                        for ((old, new) in mapOf("name" to "filename", "mimeType" to "mediaType", "bytes" to "raw", "uri" to "url")) {
                            file.get(old)?.let { part.set<JsonNode>(new, it) }
                        }
                    }
                }
            }
        }
    }
}

private fun WithA2AMethod.v1Name(): String = when (this) {
    WithA2AMethod.MESSAGE_SEND -> A2AMethods.SEND_MESSAGE_METHOD
    WithA2AMethod.MESSAGE_STREAM -> A2AMethods.SEND_STREAMING_MESSAGE_METHOD
    WithA2AMethod.TASKS_GET -> A2AMethods.GET_TASK_METHOD
    WithA2AMethod.TASKS_LIST -> A2AMethods.LIST_TASK_METHOD
    WithA2AMethod.TASKS_CANCEL -> A2AMethods.CANCEL_TASK_METHOD
    WithA2AMethod.TASKS_RESUBSCRIBE -> A2AMethods.SUBSCRIBE_TO_TASK_METHOD
    WithA2AMethod.TASKS_PUSH_NOTIFICATION_CONFIG_SET -> A2AMethods.SET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD
    WithA2AMethod.TASKS_PUSH_NOTIFICATION_CONFIG_GET -> A2AMethods.GET_TASK_PUSH_NOTIFICATION_CONFIG_METHOD
    WithA2AMethod.TASKS_PUSH_NOTIFICATION_CONFIG_LIST -> A2AMethods.LIST_TASK_PUSH_NOTIFICATION_CONFIG_METHOD
    WithA2AMethod.TASKS_PUSH_NOTIFICATION_CONFIG_DELETE -> A2AMethods.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_METHOD
    WithA2AMethod.AGENT_GET_AUTHENTICATED_EXTENDED_CARD -> A2AMethods.GET_EXTENDED_AGENT_CARD_METHOD
}
