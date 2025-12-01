// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Configuration for emitting a CloudEvent.
 *
 * Contains all data needed to build a CloudEvent using the CloudEvents SDK.
 * The ActivityExecutor uses this config to construct and publish the event.
 */
@Serializable
data class EmitConfig(
    val id: String,
    val source: String,
    val type: String,
    val time: String? = null,
    val subject: String? = null,
    val dataschema: String? = null,
    val datacontenttype: String? = null,
    val data: JsonElement? = null,
    val extensions: Map<String, String>? = null
)

/**
 * Configuration for making an HTTP call.
 *
 * Contains all data needed to execute an HTTP request.
 * Authentication is resolved at config-building time.
 */
@Serializable
data class CallHttpConfig(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: JsonElement? = null,
    val output: HTTPOutput = HTTPOutput.CONTENT,
    val redirect: Boolean = true,
    val authentication: HttpAuthentication? = null
)

/**
 * Resolved authentication data for HTTP calls.
 * Authentication policies are resolved when building the config.
 */
@Serializable
sealed class HttpAuthentication {
    @Serializable
    data class Basic(val username: String, val password: String) : HttpAuthentication()

    @Serializable
    data class Bearer(val token: String) : HttpAuthentication()

    @Serializable
    data class OAuth2(
        val token: String,
        val tokenType: String = "Bearer"
    ) : HttpAuthentication()
}

/**
 * Configuration for running a script.
 *
 * Contains all data needed to execute a script in a supported language.
 */
@Serializable
data class RunScriptConfig(
    val language: String,
    val code: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val await: Boolean = true,
    val returnType: ProcessReturnType = ProcessReturnType.STDOUT
)

/**
 * Configuration for running a shell command.
 *
 * Contains all data needed to execute a shell command.
 */
@Serializable
data class RunShellConfig(
    val command: String,
    val arguments: Map<String, String>? = null,
    val environment: Map<String, String>? = null,
    val await: Boolean = true,
    val returnType: ProcessReturnType = ProcessReturnType.STDOUT
)
