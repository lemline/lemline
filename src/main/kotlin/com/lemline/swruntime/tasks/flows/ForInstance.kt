package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.ForTask

class ForInstance(
    override val node: NodeTask<ForTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForTask>(node, parent) {

    /**
     * The collection to enumerate (calculated)
     */
    private var _forIn: List<JsonNode>? = null

    internal val forIn: List<JsonNode>
        get() = _forIn ?: evalForIn(node.task.`for`.`in`).also { _forIn = it }

    /**
     * The index into the enumeration (from state)
     */
    private var forIndex: Int
        get() = state.forIndex
        set(value) {
            state.forIndex = value
        }

    // The name of the variable used to store the current item being enumerated.
    private val forEach = node.task.`for`.each ?: "item"

    // The name of the variable used to store the index of the current item being enumerated.
    private val forAt = node.task.`for`.at ?: "index"

    override suspend fun execute() {
        // useless, but indicate we entered the node
        childIndex++
    }

    override fun reset() {
        _forIn = null
        super.reset()
    }

    override fun `continue`(): NodeInstance<*>? {
        forIndex++

        // if we reached the end, follow the then directive
        if (forIndex == forIn.size) return then()

        // else define the additional scope variable
        with(variables) {
            set<JsonNode>(forEach, forIn[forIndex])
            set<JsonNode>(forAt, IntNode(forIndex))
        }

        // test the while directive
        node.task.`while`?.let { if (!evalWhile(it)) return then() }

        // Go to Do
        return children[childIndex].also { it.rawInput = rawOutput!! }
    }

    private fun evalWhile(`while`: String): Boolean {
        val out = eval(rawOutput ?: transformedInput, `while`)
        return if (out.isBoolean) out.asBoolean() else error("'.while' condition must be a boolean, but is $out")
    }

    private fun evalForIn(forIn: String): List<JsonNode> {
        val out = eval(transformedInput, forIn)
        return if (out.isArray) out.asIterable().toList() else error("'.for.in' must be an array, but is $out")
    }
}
