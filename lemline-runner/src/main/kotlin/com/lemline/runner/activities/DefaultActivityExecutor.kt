// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.activities

import com.lemline.common.json.LemlineJson
import com.lemline.common.logger.logger
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventFactory
import com.lemline.core.processors.CallHttpConfig
import com.lemline.core.processors.EmitConfig
import com.lemline.core.processors.HttpAuthentication
import com.lemline.core.processors.RunScriptConfig
import com.lemline.core.processors.RunShellConfig
import com.lemline.core.states.WorkflowEvent.ActivityStarted
import com.lemline.core.states.WorkflowEvent.CallHttpStarted
import com.lemline.core.states.WorkflowEvent.EmitStarted
import com.lemline.core.states.WorkflowEvent.RunScriptStarted
import com.lemline.core.states.WorkflowEvent.RunShellStarted
import com.lemline.core.tasks.runs.Script
import com.lemline.core.tasks.runs.Shell
import io.cloudevents.CloudEvent
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.serverlessworkflow.api.types.HTTPArguments.HTTPOutput
import java.io.File
import java.util.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Default implementation of [ActivityExecutor] that performs real I/O operations.
 *
 * This executor handles:
 * - HTTP calls via Ktor client
 * - Script execution (JavaScript, Python)
 * - Shell command execution
 * - CloudEvent emission (via provided emitter)
 *
 * Note: Function calls (CallFunctionStarted) are NOT activities - they are handled
 * directly by the WorkflowCommandHandler as suspensions. Functions are executed
 * as synthetic workflows.
 *
 * @param emitCloudEvent Optional callback to emit CloudEvents. If not provided, emit is a no-op.
 */
class DefaultActivityExecutor(
    private val emitCloudEvent: (suspend (CloudEvent) -> Unit)? = null,
) : ActivityExecutor {

    private val logger = logger()

    private val httpClient by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(LemlineJson.json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 30000
            }
            install(HttpRedirect) {
                checkHttpMethod = false
                allowHttpsDowngrade = true
            }
            followRedirects = false
        }
    }

    override suspend fun execute(event: ActivityStarted): JsonElement = when (event) {
        is EmitStarted -> executeEmit(event.config, event.input)
        is CallHttpStarted -> executeHttp(event.config)
        is RunScriptStarted -> executeScript(event.config, event.input)
        is RunShellStarted -> executeShell(event.config, event.input)
    }

    // ========================================
    // Emit Execution
    // ========================================

    private suspend fun executeEmit(config: EmitConfig, rawOutput: JsonElement): JsonElement {
        logger.debug { "Emitting CloudEvent: type=${config.type}, source=${config.source}" }

        val cloudEvent = CloudEventFactory.build(config)

        // Emit if callback provided, otherwise no-op (for testing)
        emitCloudEvent?.invoke(cloudEvent)

        // Emit is fire-and-forget, return raw output
        return rawOutput
    }

    // ========================================
    // HTTP Execution
    // ========================================

    private suspend fun executeHttp(config: CallHttpConfig): JsonElement {
        val method = HttpMethod.parse(config.method)

        // Build URL with query parameters
        val urlBuilder = URLBuilder(config.url)
        config.query.forEach { (key, value) ->
            urlBuilder.parameters.append(key, value)
        }
        val url = urlBuilder.build()
        logger.debug { "Executing HTTP ${config.method} $url (query=${config.query}, redirect=${config.redirect})" }

        val response: HttpResponse = httpClient.config {
            followRedirects = config.redirect
        }.request(url) {
            this.method = method

            // Set headers
            config.headers.forEach { (key, value) -> header(key, value) }

            // Set authentication
            config.authentication?.let { auth ->
                when (auth) {
                    is HttpAuthentication.Basic -> {
                        val credentials = Base64.getEncoder()
                            .encodeToString("${auth.username}:${auth.password}".toByteArray())
                        header(HttpHeaders.Authorization, "Basic $credentials")
                    }

                    is HttpAuthentication.Bearer -> {
                        header(HttpHeaders.Authorization, "Bearer ${auth.token}")
                    }

                    is HttpAuthentication.OAuth2 -> {
                        header(HttpHeaders.Authorization, "${auth.tokenType} ${auth.token}")
                    }
                }
            }

            // Set body
            if (config.body != null && (method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Patch)) {
                contentType(ContentType.Application.Json)
                setBody(config.body)
            }
        }

        logger.debug { "HTTP response received: ${response.status} from $url" }

        // Check status
        if (!response.status.isSuccess()) {
            val body = response.bodyAsText()
            throw RuntimeException("HTTP ${response.status.value}: $body")
        }

        val result = when (config.output) {
            HTTPOutput.RAW -> JsonPrimitive(Base64.getEncoder().encodeToString(response.body<ByteArray>()))
            HTTPOutput.CONTENT -> parseResponseContent(response)
            HTTPOutput.RESPONSE -> buildJsonObject {
                // Include request details
                put("request", buildJsonObject {
                    put("method", JsonPrimitive(response.request.method.value.uppercase()))
                    put("uri", JsonPrimitive(response.request.url.toString()))
                    put("headers", buildJsonObject {
                        response.request.headers.names().forEach { name ->
                            put(name, JsonPrimitive(response.request.headers[name] ?: ""))
                        }
                    })
                })
                put("statusCode", JsonPrimitive(response.status.value))
                // Convert headers to map manually since toMap() is internal
                put("headers", buildJsonObject {
                    response.headers.names().forEach { name ->
                        put(name, JsonPrimitive(response.headers[name] ?: ""))
                    }
                })
                put("content", parseResponseContent(response))
            }
        }
        logger.debug { "HTTP response parsed as ${config.output}: $result" }
        return result
    }

    private suspend fun parseResponseContent(response: HttpResponse): JsonElement {
        val contentType = response.contentType()?.withoutParameters()?.toString()?.lowercase()
        val text = response.bodyAsText()

        return when {
            contentType?.contains("json") == true -> LemlineJson.json.parseToJsonElement(text)
            contentType?.contains("text") == true -> JsonPrimitive(text)
            else -> JsonPrimitive(Base64.getEncoder().encodeToString(text.toByteArray()))
        }
    }

    // ========================================
    // Script Execution
    // ========================================

    private suspend fun executeScript(config: RunScriptConfig, rawOutput: JsonElement): JsonElement {
        logger.debug { "Executing ${config.language} script" }

        val script = Script(
            script = config.code,
            language = config.language,
            arguments = config.arguments,
            environment = config.environment,
            workingDir = File(".").toPath()
        )

        if (!config.await) {
            val process = script.executeAsync()
            logger.debug { "Launched script asynchronously with PID: ${process.pid()}" }
            return rawOutput
        }

        val result = script.execute()
        logger.debug { "Script completed with exit code: ${result.code}" }

        return result.get(config.returnType)
    }

    // ========================================
    // Shell Execution
    // ========================================

    private suspend fun executeShell(config: RunShellConfig, rawOutput: JsonElement): JsonElement {
        logger.debug { "Executing shell command: ${config.command}" }

        val shell = Shell(
            command = config.command,
            arguments = config.arguments,
            environment = config.environment
        )

        if (!config.await) {
            val process = shell.executeAsync()
            logger.debug { "Launched shell command asynchronously with PID: ${process.pid()}" }
            return rawOutput
        }

        val result = shell.execute()
        logger.debug { "Shell command completed with exit code: ${result.code}" }

        return result.get(config.returnType)
    }
}
