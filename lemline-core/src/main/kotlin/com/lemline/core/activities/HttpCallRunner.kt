// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.activities.calls.HttpCall
import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.instances.CallHttpInstance
import com.lemline.core.json.LemlineJson
import com.lemline.core.json.LemlineJson.toJsonPrimitive
import com.lemline.core.utils.getAuthenticationPolicyByName
import com.lemline.core.utils.toAuthenticationPolicy
import com.lemline.core.utils.toSecret
import com.lemline.core.utils.toUrl
import io.ktor.http.*
import io.serverlessworkflow.api.types.HTTPArguments
import kotlinx.serialization.json.JsonElement

class HttpCallRunner : ActivityRunner<CallHttpInstance> {
    override suspend fun run(instance: CallHttpInstance) {
        // The HttpCall helper is instantiated here, using method references from the instance
        val httpCall = HttpCall(
            getSecretByName = instance::toSecret,
            getAuthenticationPolicyByName = instance::getAuthenticationPolicyByName,
            onError = instance::onError,
        )

        instance.logInfo { "Executing HTTP call: ${instance.node.name}" }

        val httpArgs = instance.node.task.with

        // Extract method
        val method = when (httpArgs.method.uppercase()) {
            "POST" -> HttpMethod.Post
            "GET" -> HttpMethod.Get
            "PUT" -> HttpMethod.Put
            "DELETE" -> HttpMethod.Delete
            else -> instance.onError(WorkflowErrorType.CONFIGURATION, "Unsupported HTTP method: ${httpArgs.method}")
        }

        // Extract other arguments using the instance
        val endpoint = instance.toUrl(httpArgs.endpoint)
        val authentication = instance.toAuthenticationPolicy(httpArgs.endpoint)
        val headers = httpArgs.headers?.additionalProperties?.mapValues { it.value.toJsonPrimitive().content }
            ?: emptyMap()
        val body: JsonElement? = httpArgs.body?.let { with(LemlineJson) { it.toJsonElement() } }
        val output: HTTPArguments.HTTPOutput = httpArgs.output ?: HTTPArguments.HTTPOutput.CONTENT
        val redirect = httpArgs.isRedirect

        // Build the URL with query parameters
        val urlBuilder = URLBuilder(endpoint)
        httpArgs.query
            ?.additionalProperties
            ?.mapValues { it.value.toJsonPrimitive().content }
            ?.forEach { (key, value) ->
                urlBuilder.parameters.append(key, value)
            }
        val url = urlBuilder.build()

        // Execute the call and set the rawOutput on the instance
        instance.rawOutput = httpCall.execute(
            method = method,
            url = url,
            headers = headers,
            body = body,
            output = output,
            redirect = redirect,
            authentication = authentication,
        )
    }
}
