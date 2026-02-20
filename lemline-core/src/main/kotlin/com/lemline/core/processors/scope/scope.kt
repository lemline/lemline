// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors.scope

import com.lemline.core.nodes.Node
import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

typealias Scope = JsonObject

/**
 * Merges the current `Scope` map with another `Scope` map.
 * Entries from the other map will overwrite entries in the current map if the keys are the same.
 *
 * @param other Another `Scope` map to merge with the current map. If null, only the current map will be returned.
 * @return A new `Scope` map containing the merged key-value pairs from both maps.
 */
internal fun Scope.merge(other: Scope?): Scope =
    buildJsonObject {
        this@merge.forEach { put(it.key, it.value) }  // Put base scope first
        other?.forEach { put(it.key, it.value) }      // Then overwrite with another scope
    }

/**
 * Creates a new Scope with the given task information.
 */
fun Scope.withTask(
    node: Node<*>,
    startedAt: Instant,
    rawInput: JsonElement = JsonNull
): Scope {
    val task = buildJsonObject {
        put("name", JsonPrimitive(node.name))
        // TODO Current implementation does not follow spec
        put("reference", JsonPrimitive(node.position.toString()))
        put("definition", node.definition)
        put(
            "startedAt", buildJsonObject {
                put("iso8601", JsonPrimitive(startedAt.toString()))
                put("epoch", buildJsonObject {
                    put("seconds", JsonPrimitive(startedAt.epochSeconds))
                    put("milliseconds", JsonPrimitive(startedAt.toEpochMilliseconds()))
                })
            }
        )
        put("input", rawInput)
    }

    return JsonObject(this + ("task" to task))
}

/**
 * Creates a new Scope with the given transformed input.
 */
fun Scope.withTransformedInput(
    transformedInput: JsonElement
) = JsonObject(this + ("input" to transformedInput))

/**
 * Creates a new Scope with the given raw output.
 */
fun Scope.withRawOutput(
    rawOutput: JsonElement
): Scope {
    val task = get("task")?.jsonObject ?: error("Scope missing 'task' key")
    return JsonObject(this + ("task" to JsonObject(task + ("output" to rawOutput))))
}

/**
 * Creates a new Scope with the given transformed output.
 */
fun Scope.withTransformedOutput(
    transformedOutput: JsonElement
) = JsonObject(this + ("output" to transformedOutput))
