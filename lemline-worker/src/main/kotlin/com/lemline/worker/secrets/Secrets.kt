// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.secrets

import com.lemline.core.workflows.WorkflowIndex
import com.lemline.core.workflows.index
import com.lemline.worker.system.System
import io.serverlessworkflow.api.types.Workflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

object Secrets {

    private val secretsCache = ConcurrentHashMap<WorkflowIndex, Map<String, JsonElement>>()

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return A map of secret names to their JsonNode values from environment variables
     * @throws IllegalStateException if a required secret is not found in environment variables
     */
    fun get(workflow: Workflow): Map<String, JsonElement> =
        secretsCache.getOrPut(workflow.index) {
            workflow.use?.secrets?.associateWith { secretName ->
                val value = System.getEnv(secretName)
                    ?: error("Required secret '$secretName' not found in environment variables")
                try {
                    Json.decodeFromString<JsonObject>(value)
                } catch (e: Exception) {
                    JsonPrimitive(value)
                }
            } ?: emptyMap()
        }

    fun error(message: String): Nothing = throw IllegalStateException(message)

}
