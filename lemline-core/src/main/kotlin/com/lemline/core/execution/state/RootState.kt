// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)

package com.lemline.core.execution.state

import com.lemline.core.RuntimeDescriptor
import com.lemline.core.expressions.scopes.WorkflowDescriptor
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class RootState(
    override val startedAt: Instant,
    val context: ExprArgs = JsonObject(emptyMap()),
) : NodeState() {

    @Transient
    var secrets: Map<String, String> = emptyMap()

    @Transient
    lateinit var workflowDescriptor: WorkflowDescriptor

    override val exprArgs: ExprArgs by lazy {
        buildJsonObject {
            put("context", context)
            put("runtime", Json.encodeToJsonElement(RuntimeDescriptor))
            put("secrets", Json.encodeToJsonElement(secrets))
            put("workflow", Json.encodeToJsonElement(workflowDescriptor))
        }
    }
}
