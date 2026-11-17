// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.merge
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.Instant
import kotlin.time.toJavaInstant

data class RootState(
    override val startedAt: Instant,
    val workflowId: WorkflowId,
    val workflowInput: JsonNode = buildJsonObject {},
    val context: Scope = buildJsonObject {},
    val hasWaitingParent: Boolean = false,
) : NodeState() {

    // Resolved per command; never included in RootStateProto.
    private var secrets: ObjectNode = buildJsonObject {}

    // Rehydrated from the loaded workflow on each command; intentionally absent from RootStateProto.
    private lateinit var definition: ObjectNode

    override val scope: Scope by lazy {
        buildJsonObject {
            put("context", context)
            // The former Kotlin singleton serializer emitted an empty object.
            // Preserve that scope representation during this JSON migration.
            put("runtime", buildJsonObject {})
            put("secrets", secrets)
            put("workflow", buildJsonObject {
                put("id", workflowId.toString())
                put("definition", definition)
                put("input", workflowInput)
                put("startedAt", LemlineJson.encodeToElement(DateTimeDescriptor.from(startedAt.toJavaInstant())))
            })
        }
    }

    /**
     * Creates a new RootState with merged context, copying all transient fields.
     *
     * The new context is merged with the existing context, where new values
     * override existing ones for the same keys.
     *
     * @param newContext The context to merge with the existing context
     * @return A new RootState with the merged context
     */
    fun withContext(newContext: Scope): RootState = copyRuntimeFieldsTo(copy(context = context.merge(newContext)))

    fun withIdentity(workflowId: WorkflowId, startedAt: Instant): RootState =
        copyRuntimeFieldsTo(copy(workflowId = workflowId, startedAt = startedAt))

    fun withDefinition(definition: ObjectNode): RootState = copyRuntimeFieldsTo(copy()).also {
        it.definition = definition
    }

    fun withSecrets(secrets: ObjectNode): RootState = copyRuntimeFieldsTo(copy()).also {
        it.secrets = secrets
    }

    private fun copyRuntimeFieldsTo(state: RootState): RootState = state.also {
        it.secrets = secrets
        if (this::definition.isInitialized) it.definition = definition
    }
}
