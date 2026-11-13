// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.core.expressions.scopes.RuntimeDescriptor
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.processors.scope.Scope
import com.lemline.core.processors.scope.merge
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

data class RootState(
    override val startedAt: Instant,
    val workflowId: WorkflowId,
    val workflowInput: JsonElement = buildJsonObject {},
    val context: Scope = buildJsonObject {},
    val hasWaitingParent: Boolean = false,
) : NodeState() {

    lateinit var secrets: Map<String, String>

    // Rehydrated from the loaded workflow on each command; intentionally absent from RootStateProto.
    private lateinit var definition: JsonObject

    private val workflowDescriptor
        get() = WorkflowDescriptor(
            id = workflowId.toString(),
            definition = definition,
            input = workflowInput,
            startedAt = LemlineJson.encodeToElement(
                DateTimeDescriptor.from(startedAt.toJavaInstant())
            ),
        )

    override val scope: Scope by lazy {
        buildJsonObject {
            put("context", context)
            put("runtime", Json.encodeToJsonElement(RuntimeDescriptor))
            //put("secrets", Json.encodeToJsonElement(secrets))
            put("workflow", Json.encodeToJsonElement(workflowDescriptor))
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

    fun withDefinition(definition: JsonObject): RootState = copyRuntimeFieldsTo(copy()).also {
        it.definition = definition
    }

    private fun copyRuntimeFieldsTo(state: RootState): RootState = state.also {
        if (this::secrets.isInitialized) it.secrets = secrets
        if (this::definition.isInitialized) it.definition = definition
    }
}
