// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.processors

import com.lemline.core.expressions.scopes.TaskDescriptor
import com.lemline.core.nodes.Node
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class NodeDescriptor private constructor(
    val name: String,
    val reference: String,
    val definition: JsonObject,
) {
    /**
     * The task's start time in milliseconds.
     * in DateTimeDescriptor format
     */
    var startedAt: JsonObject? = null

    /**
     * Raw input for this task (before transformation).
     * Set by parent when entering or re-entering this node.
     */
    var rawInput: JsonElement? = null

    /**
     * The task's input after `input.from` transformation
     * (calculated on demand from rawInput and runtime descriptor)
     */
    var transformedInput: JsonElement? = null

    /**
     * Raw output from this task's action (before transformation).
     * Set after execute() completes.
     */
    var rawOutput: JsonElement? = null

    /**
     * The task's output after `output.from` transformation
     * (calculated on demand from rawOutput and runtime descriptor)
     */
    var transformedOutput: JsonElement? = null

    val taskDescriptor: TaskDescriptor
        get() = TaskDescriptor(
            name = name,
            reference = reference,
            definition = definition,
            startedAt = startedAt,
            input = rawInput,
            output = rawOutput,
        )

    companion object Companion {
        fun from(node: Node<*>) = NodeDescriptor(
            node.name,
            node.reference,
            node.definition
        )
    }

}
