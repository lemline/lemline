package com.lemline.core.expressions.scopes

import com.lemline.core.RuntimeDescriptor
import com.lemline.core.json.LemlineJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Data class representing a scope.
 *
 * @property context The context JSON object for the scope.
 * @property input The input JSON element for the scope.
 * @property output The output JSON element for the scope.
 * @property secrets A map of secrets associated with the scope.
 * @property authorization The authorization descriptor for the scope.
 * @property task The task descriptor for the scope.
 * @property workflow The workflow descriptor for the scope.
 * @property runtime The runtime descriptor for the scope.
 */
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
    /**
     * Converts the scope to a JSON object.
     *
     * @return The JSON object representation of the scope.
     */
    fun toJsonObject(): JsonObject = LemlineJson.encodeToElement(this) as JsonObject
}