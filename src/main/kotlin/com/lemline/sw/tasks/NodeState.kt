package com.lemline.sw.tasks

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import java.time.Instant

private val jsonFactory = JsonNodeFactory.instance

/**
 * Represents a node's states.
 * Contain states variables for the task during execution.
 * Some value such as forIndex as specific to a specific task
 */
data class NodeState(
    var variables: ObjectNode = jsonFactory.objectNode(),
    var attemptIndex: Int = ATTEMPT_INDEX_DEFAULT,
    var childIndex: Int = CHILD_INDEX_DEFAULT,
    var rawInput: JsonNode? = null,
    var rawOutput: JsonNode? = null,
    var context: ObjectNode = jsonFactory.objectNode(),
    var workflowId: String? = null,
    var startedAt: DateTimeDescriptor? = null,
    var forIndex: Int = FOR_INDEX_DEFAULT,
) {
    fun toJson(): ObjectNode? {
        val json = jsonFactory.objectNode()
        if (!variables.isEmpty) json.set<JsonNode>(VARIABLES, variables)
        if (attemptIndex != ATTEMPT_INDEX_DEFAULT) json.set<IntNode>(ATTEMPT_INDEX, IntNode(attemptIndex))
        if (childIndex != CHILD_INDEX_DEFAULT) json.set<IntNode>(CHILD_INDEX, IntNode(childIndex))
        rawInput?.let { json.set<JsonNode>(RAW_INPUT, it) }
        rawOutput?.let { json.set<JsonNode>(RAW_OUTPUT, it) }
        if (!context.isEmpty) json.set<JsonNode>(CONTEXT, context)
        workflowId?.let { json.set<JsonNode>(WORKFLOW_ID, TextNode(it)) }
        startedAt?.let { json.set<JsonNode>(STARTED_AT, TextNode(it.iso8601())) }
        if (forIndex != FOR_INDEX_DEFAULT) json.set<IntNode>(FOR_INDEX, IntNode(forIndex))

        return if (json.isEmpty) null else json
    }

    /**
     * Those constants MUST NOT be changed to ensure backward compatibility of messages
     */
    companion object {
        fun fromJson(json: ObjectNode) = NodeState().apply {
            json.get(VARIABLES)?.let { variables ->
                if (variables is ObjectNode) this.variables = variables
                else throw IllegalArgumentException("variables must be an ObjectNode")
            }
            json.get(ATTEMPT_INDEX)?.let { attemptIndex ->
                if (attemptIndex is IntNode) this.attemptIndex = attemptIndex.intValue()
                else throw IllegalArgumentException("attemptIndex must be an IntNode")
            }
            json.get(CHILD_INDEX)?.let { childIndex ->
                if (childIndex is IntNode) this.childIndex = childIndex.intValue()
                else throw IllegalArgumentException("childIndex must be an IntNode")
            }
            json.get(RAW_INPUT)?.let { rawInput ->
                this.rawInput = rawInput
            }
            json.get(RAW_OUTPUT)?.let { rawOutput ->
                this.rawOutput = rawOutput
            }
            json.get(CONTEXT)?.let { context ->
                if (context is ObjectNode) this.context = context
                else throw IllegalArgumentException("context must be an ObjectNode")
            }
            json.get(WORKFLOW_ID)?.let { workflowId ->
                if (workflowId is TextNode) this.workflowId = workflowId.textValue()
            }
            json.get(STARTED_AT)?.let { startedAt ->
                if (startedAt is TextNode) this.startedAt =
                    DateTimeDescriptor.from(Instant.parse(startedAt.textValue()))
                else throw IllegalArgumentException("startedAt must be an TextNode")
            }
            json.get(FOR_INDEX)?.let { forIndex ->
                if (forIndex is IntNode) this.forIndex = forIndex.intValue()
                else throw IllegalArgumentException("forIndex must be an IntNode")
            }
        }

        const val CHILD_INDEX = "i"
        const val ATTEMPT_INDEX = "try"
        const val VARIABLES = "var"
        const val RAW_INPUT = "inp"
        const val RAW_OUTPUT = "out"
        const val CONTEXT = "ctx"
        const val WORKFLOW_ID = "wid"
        const val STARTED_AT = "sat"
        const val FOR_INDEX = "fori"

        const val CHILD_INDEX_DEFAULT = -1
        const val ATTEMPT_INDEX_DEFAULT = 0
        const val FOR_INDEX_DEFAULT = -1
    }
}
