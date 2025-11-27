// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.states

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WorkflowId
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import com.lemline.core.orchestrator.context.Scope
import com.lemline.core.workflows.RuntimeDescriptor
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class RootState(
    override val startedAt: Instant,
    val workflowId: WorkflowId,
    val workflowInput: JsonElement = buildJsonObject {},
    val context: Scope = buildJsonObject {},
    val hasWaitingParent: Boolean = false,
    val workflowStep: Int = 0,
) : BaseState() {

    @Transient
    lateinit var secrets: Map<String, String>

    private val workflowDescriptor
        get() = WorkflowDescriptor(
            id = workflowId.toString(),
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
     * Creates a new RootState with updated context, copying all transient fields.
     *
     * @param newContext The new context to use
     * @return A new RootState with the updated context
     */
    fun copyWithContext(newContext: Scope): RootState {
        val state = copy(context = newContext)
        // copy also transient fields
        if (this::secrets.isInitialized) state.secrets = this.secrets

        return state
    }
}
