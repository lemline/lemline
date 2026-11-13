// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.secrets

import com.lemline.runner.system.System
import io.serverlessworkflow.api.types.Workflow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object Secrets {

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return The currently available declared secrets; the orchestrator faults missing ones.
     */
    fun getForWorkflow(workflow: Workflow): JsonObject = JsonObject(
        workflow.use?.secrets?.mapNotNull { secretName ->
            val value = System.getEnv(secretName) ?: return@mapNotNull null
            try {
                secretName to Json.decodeFromString<JsonObject>(value)
            } catch (_: Exception) {
                secretName to JsonPrimitive(value)
            }
        }?.toMap() ?: emptyMap<String, JsonElement>()
    )
}
