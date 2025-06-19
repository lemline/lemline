// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.common.logger
import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.utils.getAuthenticationPolicyByName
import com.lemline.core.utils.toAuthenticationPolicy
import com.lemline.core.utils.toSecret
import com.lemline.core.utils.toUrl
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import kotlinx.serialization.json.JsonElement

class CallHttpInstance(
    override val node: Node<CallHTTP>,
    override val parent: NodeInstance<*>
) : NodeInstance<CallHTTP>(node, parent) {

    private val httpCall = HttpCall(
        getSecretByName = this::toSecret,
        getAuthenticationPolicyByName = this::getAuthenticationPolicyByName,
        onError = this::onError,
    )

    private val logger = logger()

    override suspend fun run() {
        logger.info("Executing HTTP call: ${node.name}")

        val httpArgs = node.task.with

        // Extract method
        val method = httpArgs.method

        // Extract endpoint URL and authentication if available
        val endpoint = toUrl(httpArgs.endpoint)

        // Extract authentication from the endpoint
        val authentication = toAuthenticationPolicy(httpArgs.endpoint)

        // Extract headers
        val headers = LemlineJson.encodeToString(httpArgs.headers)

        // Extract body
        val body: JsonElement? = httpArgs.body?.let { with(LemlineJson) { it.toJsonElement() } }

        // Extract query parameters
        val query: Map<String, String> = LemlineJson.encodeToString(httpArgs.query)

        // Extract output format
        val output: HTTPOutput = httpArgs.output ?: HTTPOutput.CONTENT

        // Extract redirect flag
        val redirect = httpArgs.isRedirect

        // --- DEBUGGING START ---
        logger.info("Passing authentication object to HttpCall.execute: $authentication")
        // --- DEBUGGING END ---

        // Execute the HTTP call directly using the suspendable function
        this.rawOutput = httpCall.execute(
            method = method,
            endpoint = endpoint,
            headers = headers,
            body = body,
            query = query,
            output = output,
            redirect = redirect,
            authentication = authentication,
        )
    }
}
