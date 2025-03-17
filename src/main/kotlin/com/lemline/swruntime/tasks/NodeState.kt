package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import net.thisptr.jackson.jq.Scope as JQScope

/**
 * Represents a node's scope.
 * Contains state variables for the task during execution.
 */
@JvmInline
value class NodeState(
    private val variables: MutableMap<String, JsonNode> = mutableMapOf()
) : MutableMap<String, JsonNode> by variables {
    fun toJQScope() = JQScope.newEmptyScope().apply {
        variables.forEach { (key, value) -> setValue(key, value) }
    }
}