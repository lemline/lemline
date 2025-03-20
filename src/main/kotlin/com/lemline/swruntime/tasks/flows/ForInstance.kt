package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.ForTask

class ForInstance(
    override val node: NodeTask<ForTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForTask>(node, parent) {

    // The collection to enumerate.
    private val forIn by lazy { evalForIn(node.task.`for`.`in`) }

    // The name of the variable used to store the current item being enumerated.
    private val forEach = node.task.`for`.each ?: "item"

    // The name of the variable used to store the index of the current item being enumerated.
    private val forAt = node.task.`for`.at ?: "index"

    override fun `continue`(): NodeInstance<*>? {

        // if while is defined and false, leave and apply 'then' directive
        node.task.`while`?.let { if (!evalWhile(it)) return then() }

        childIndex++

        return when (childIndex) {
            0 -> children[0].also { it.rawInput = transformedInput }
            children.size -> then()
            else -> children[childIndex].also { it.rawInput = rawOutput }
        }
    }

    private fun evalWhile(`while`: String): Boolean {
        val out = JQExpression.eval(rawOutput ?: transformedInput!!, `while`, scope)
        return if (out.isBoolean) out.asBoolean() else error("'.while' condition must be a boolean, but is $out")
    }

    private fun evalForIn(forIn: String): List<JsonNode> {
        val out = JQExpression.eval(transformedInput!!, forIn, scope)
        return if (out.isArray) out.asIterable().toList() else error("'.for.in' must be an array, but is $out")
    }
}
