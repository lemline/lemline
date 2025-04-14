package com.lemline.core.nodes.flows

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.ForTask
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ForInstance(
    override val node: Node<ForTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForTask>(node, parent) {

    /**
     * The collection to enumerate (calculated)
     */
    private var _forIn: List<JsonElement>? = null

    private val forIn: List<JsonElement>
        get() = _forIn ?: evalForIn(node.task.`for`.`in`).also { _forIn = it }

    /**
     * The index into the enumeration (from state)
     */
    private var forIndex: Int
        get() = state.forIndex
        set(value) {
            state.forIndex = value
        }

    // The name of the variable used to store the currentNodeInstance item being enumerated.
    private val forEach = node.task.`for`.each ?: "item"

    // The name of the variable used to store the index of the currentNodeInstance item being enumerated.
    private val forAt = node.task.`for`.at ?: "index"

    override fun reset() {
        _forIn = null
        super.reset()
    }

    override suspend fun execute() {
        // useless, but indicate we entered the node
        childIndex++
        // set rawOutput
        super.execute()
    }

    override fun `continue`(): NodeInstance<*>? {
        forIndex++

        // if we reached the end, follow the then directive
        if (forIndex == forIn.size) return then()

        // else define the additional scope variable
        variables = JsonObject(
            mapOf(
                forEach to forIn[forIndex],
                forAt to JsonPrimitive(forIndex)
            )
        )

        // test the while directive
        node.task.`while`?.let { if (!evalWhile(it)) return then() }

        // Go to Do
        return children[childIndex].also { it.rawInput = rawOutput!! }
    }

    private fun evalWhile(`while`: String) = evalBoolean(rawOutput ?: transformedInput, `while`, "while")

    private fun evalForIn(forIn: String) = evalList(transformedInput, forIn, "for.in")
}
