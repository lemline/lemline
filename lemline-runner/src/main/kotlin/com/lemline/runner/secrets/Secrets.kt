// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.secrets

import com.lemline.runner.system.System
import io.serverlessworkflow.api.types.Workflow
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Secrets {

    private val secretsCache = ConcurrentHashMap<String, Map<String, JsonElement>>()

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return A map of secret names to their JsonNode values from environment variables
     * @throws IllegalStateException if a required secret is not found in environment variables
     */
    fun getForWorkflow(workflow: Workflow): Map<String, JsonElement> = secretsCache.getOrPut(getWorkflowKey(workflow)) {
        workflow.use?.secrets?.associateWith { secretName ->
            val value = System.getEnv(secretName)
                ?: error("Required secret '$secretName' not found in environment variables")
            try {
                Json.decodeFromString<JsonObject>(value)
            } catch (_: Exception) {
                JsonPrimitive(value)
            }
        } ?: emptyMap()
    }

    private fun getWorkflowKey(workflow: Workflow): String {
        val ns = workflow.document.namespace ?: "default"
        val name = workflow.document.name
        val version = workflow.document.version
        return "$ns:$name:$version"
    }

    fun error(message: String): Nothing = throw IllegalStateException(message)
}
