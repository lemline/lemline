package com.lemline.sw.expressions.scopes

import com.lemline.common.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class Scope(
    val context: JsonObject? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val secrets: Map<String, JsonElement> = emptyMap(),
    val authorization: AuthorizationDescriptor? = null,
    val task: TaskDescriptor? = null,
    val workflow: WorkflowDescriptor? = null,
    val runtime: RuntimeDescriptor? = null,
) {
    fun toJsonObject(): JsonObject = Json.encodeToElement(this) as JsonObject
}