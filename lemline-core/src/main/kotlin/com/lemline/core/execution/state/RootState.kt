// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import com.lemline.common.json.LemlineJson
import com.lemline.core.RuntimeDescriptor
import com.lemline.core.expressions.scopes.WorkflowDescriptor
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
    val id: String,
    val input: JsonElement,
    val context: Scope = buildJsonObject {},
    val hasRun: Boolean,
) : NodeState() {

    @Transient
    lateinit var secrets: Map<String, String>

    private val workflowDescriptor
        get() = WorkflowDescriptor(
            id, input,
            LemlineJson.encodeToElement(
                DateTimeDescriptor.from(startedAt.toJavaInstant())
            ),
        )

    // Compute scope fresh each time to reflect current context
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
        val state = RootState(
            startedAt = startedAt,
            id = id,
            input = input,
            context = newContext,
            hasRun = hasRun,
        )
        // copy also transient fields
        if (this::secrets.isInitialized) state.secrets = this.secrets

        return state
    }
}
