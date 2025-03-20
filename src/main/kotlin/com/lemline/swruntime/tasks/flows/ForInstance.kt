package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
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

    private var forIndex: Int = -1

    override suspend fun execute() {
        childIndex++
    }

    override fun `continue`(): NodeInstance<*>? {
        forIndex++

        // if we reached the end, follow the then directive
        if (forIndex == forIn.size) return then()

        // else define the additional scope variable
        this.customScope.set<JsonNode>(forEach, forIn[forIndex])
        this.customScope.set<JsonNode>(forAt, IntNode(forIndex))

        // test the while directive
        node.task.`while`?.let { if (!evalWhile(it)) return then() }

        // Do
        return children[childIndex].also { it.rawInput = rawOutput }
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
