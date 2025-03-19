package com.lemline.swruntime.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import io.serverlessworkflow.impl.json.JsonUtils
import java.time.Instant

/**
 * Represents a node's state. Contains state variables for the task during execution.
 */
@JvmInline
value class NodeState(
    private val map: MutableMap<String, JsonNode> = mutableMapOf()
) {
    fun setVariables(scope: ObjectNode) {
        map[VARIABLES] = scope
    }

    fun setIndex(index: Int) {
        map[INDEX] = IntNode(index)
    }

    fun setRawInput(input: JsonNode) {
        map[RAW_INPUT] = input
    }

    fun setRawOutput(output: JsonNode) {
        map[RAW_OUTPUT] = output
    }

    fun setContext(context: ObjectNode) {
        map[CONTEXT] = context
    }

    fun setId(id: String) {
        map[ID] = TextNode(id)
    }

    fun setStartedAt(startedAt: DateTimeDescriptor) {
        map[STARTED_AT] = TextNode(startedAt.iso8601())
    }

    fun getVariables(): ObjectNode = (map[VARIABLES] as ObjectNode?) ?: JsonUtils.`object`()
    fun getIndex(): Int = map[INDEX]?.asInt() ?: -1
    fun getRawInput(): JsonNode? = map[RAW_INPUT]
    fun getRawOutput(): JsonNode? = map[RAW_OUTPUT]
    fun getContext(): ObjectNode? = map[CONTEXT] as ObjectNode?
    fun getId(): String = map[ID]!!.asText()
    fun getStartedAt(): DateTimeDescriptor = DateTimeDescriptor.from(Instant.parse(map[STARTED_AT]!!.asText()))

    /**
     * Those constants MUST NOT be changed to ensure backward compatibility of messages
     */
    companion object {
        const val INDEX = "index"
        const val VARIABLES = "variables"
        const val RAW_INPUT = "rawInput"
        const val RAW_OUTPUT = "rawOutput"
        const val CONTEXT = "context"
        const val ID = "id"
        const val STARTED_AT = "startedAt"
    }
}