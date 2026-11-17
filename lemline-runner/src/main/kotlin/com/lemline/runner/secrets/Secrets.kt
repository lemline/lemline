// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.secrets

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.runner.system.System
import io.serverlessworkflow.api.types.Workflow

object Secrets {

    /**
     * Gets the secrets values from environment variables based on the workflow's secrets configuration.
     * If a secret value is a valid JSON string, it will be parsed as a JSON object.
     *
     * @param workflow The workflow definition containing secrets configuration
     * @return The currently available declared secrets; the orchestrator faults missing ones.
     */
    fun getForWorkflow(workflow: Workflow): ObjectNode = ObjectNode(
        workflow.use?.secrets?.mapNotNull { secretName ->
            val value = System.getEnv(secretName) ?: return@mapNotNull null
            try {
                secretName to LemlineJson.decodeFromString<ObjectNode>(value)
            } catch (_: Exception) {
                secretName to jsonValue(value)
            }
        }?.toMap() ?: emptyMap<String, JsonNode>()
    )
}
