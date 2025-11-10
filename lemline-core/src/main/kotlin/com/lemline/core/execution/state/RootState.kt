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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class RootState(
    override val startedAt: Instant,
    val id: String,
    val input: JsonElement,
    val context: Scope = buildJsonObject {},
    val hasRun: Boolean = false,
) : NodeState() {

    @Transient
    var secrets: Map<String, String> = emptyMap()

    @Transient
    lateinit var definition: JsonObject

    override val scope: Scope by lazy {
        buildJsonObject {
            put("context", context)
            put("runtime", Json.encodeToJsonElement(RuntimeDescriptor))
            put("secrets", Json.encodeToJsonElement(secrets))
            put(
                "workflow", Json.encodeToJsonElement(
                    WorkflowDescriptor(
                        id, definition, input,
                        LemlineJson.encodeToElement(
                            DateTimeDescriptor.from(startedAt.toJavaInstant())
                        ),
                    )
                )
            )
        }
    }
}
