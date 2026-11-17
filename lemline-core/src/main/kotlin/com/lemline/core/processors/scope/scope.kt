// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors.scope

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import com.lemline.core.nodes.Node
import com.lemline.core.states.NodeStack
import kotlin.time.Instant

typealias Scope = ObjectNode

/**
 * Merges the current `Scope` map with another `Scope` map.
 * Entries from the other map will overwrite entries in the current map if the keys are the same.
 *
 * @param other Another `Scope` map to merge with the current map. If null, only the current map will be returned.
 * @return A new `Scope` map containing the merged key-value pairs from both maps.
 */
internal fun Scope.merge(other: Scope?): Scope =
    buildJsonObject {
        this@merge.properties().forEach { put(it.key, it.value) }  // Put base scope first
        other?.properties()?.forEach { put(it.key, it.value) }      // Then overwrite with another scope
    }

/**
 * Creates a new Scope with the given task information.
 */
fun Scope.withTask(
    node: Node<*>,
    startedAt: Instant,
    rawInput: JsonNode = NullNode.instance
): Scope {
    val task = buildJsonObject {
        put("name", jsonValue(node.name))
        // TODO Current implementation does not follow spec
        put("reference", jsonValue(node.position.toString()))
        put("definition", node.definition)
        put(
            "startedAt", buildJsonObject {
                put("iso8601", jsonValue(startedAt.toString()))
                put("epoch", buildJsonObject {
                    put("seconds", jsonValue(startedAt.epochSeconds))
                    put("milliseconds", jsonValue(startedAt.toEpochMilliseconds()))
                })
            }
        )
        put("input", rawInput)
    }

    return ObjectNode(this + ("task" to task))
}

/**
 * Creates a new Scope with the given transformed input.
 */
fun Scope.withTransformedInput(
    transformedInput: JsonNode
) = ObjectNode(this + ("input" to transformedInput))

/**
 * Creates a new Scope with the given raw output.
 */
fun Scope.withRawOutput(
    rawOutput: JsonNode
): Scope {
    if (get("_extensionTaskScope") == jsonValue(true)) return this
    val task = get("task")?.jsonObject ?: error("Scope missing 'task' key")
    return ObjectNode(this + ("task" to ObjectNode(task + ("output" to rawOutput))))
}

/**
 * Creates a new Scope with the given transformed output.
 */
fun Scope.withTransformedOutput(
    transformedOutput: JsonNode
) = ObjectNode(this + ("output" to transformedOutput))

/** Reconstructs $task from durable data while keeping local scope variables. */
fun NodeStack.taskScope(node: Node<*>, startedAt: Instant = currentState.startedAt, rawInput: JsonNode = NullNode.instance): Scope {
    val owner = node.extensionOwner ?: node
    val frame = this[owner.position]
    val extension = frame?.extension
    var scope = stateScope.withTask(owner, frame?.state?.startedAt ?: startedAt, extension?.rawInput ?: rawInput)
    extension?.preparedResult?.let { scope = scope.withRawOutput(it.rawOutput) }
    if (node.extensionOwner != null) scope = ObjectNode(scope + ("_extensionTaskScope" to jsonValue(true)))
    return scope
}
