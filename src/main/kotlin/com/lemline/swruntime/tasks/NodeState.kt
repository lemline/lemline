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

    init {
        init()
    }

    private fun init() {
        setVariables(JsonUtils.`object`())
        setContext(JsonUtils.`object`())
    }

    fun reset() {
        map.clear()
        init()
    }

    fun setVariables(scope: ObjectNode) {
        map[VARIABLES] = scope
    }

    fun setAttemptIndex(index: Int) {
        if (index > ATTEMPT_INDEX_DEFAULT) map[ATTEMPT_INDEX] = IntNode(index)
    }

    fun setChildIndex(index: Int) {
        if (index > CHILD_INDEX_DEFAULT) map[CHILD_INDEX] = IntNode(index)
    }

    fun setRawInput(input: JsonNode?) {
        input?.let { map[RAW_INPUT] = it }
    }

    fun setRawOutput(output: JsonNode?) {
        output?.let { map[RAW_OUTPUT] = it }
    }

    fun setContext(context: ObjectNode) {
        map[CONTEXT] = context
    }

    fun setWorkflowId(id: String) {
        map[WORKFLOW_ID] = TextNode(id)
    }

    fun setStartedAt(startedAt: DateTimeDescriptor?) {
        startedAt?.let { map[STARTED_AT] = TextNode(it.iso8601()) }
    }

    fun getVariables(): ObjectNode = map[VARIABLES] as ObjectNode
    fun getChildIndex(): Int = map[CHILD_INDEX]?.asInt() ?: CHILD_INDEX_DEFAULT
    fun getAttemptIndex(): Int = map[ATTEMPT_INDEX]?.asInt() ?: ATTEMPT_INDEX_DEFAULT
    fun getRawInput(): JsonNode? = map[RAW_INPUT]
    fun getRawOutput(): JsonNode? = map[RAW_OUTPUT]
    fun getContext(): ObjectNode = map[CONTEXT] as ObjectNode
    fun getWorkflowId(): String = map[WORKFLOW_ID]!!.asText()
    fun getStartedAt(): DateTimeDescriptor? =
        map[STARTED_AT]?.let { DateTimeDescriptor.from(Instant.parse(it.asText())) }

    /**
     * Those constants MUST NOT be changed to ensure backward compatibility of messages
     */
    companion object {
        const val CHILD_INDEX = "child"
        const val ATTEMPT_INDEX = "retry"
        const val VARIABLES = "var"
        const val RAW_INPUT = "in"
        const val RAW_OUTPUT = "out"
        const val CONTEXT = "ctx"
        const val WORKFLOW_ID = "id"
        const val STARTED_AT = "at"

        const val CHILD_INDEX_DEFAULT = -1
        const val ATTEMPT_INDEX_DEFAULT = 0
    }
}