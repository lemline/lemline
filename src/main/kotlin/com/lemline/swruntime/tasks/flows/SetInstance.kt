package com.lemline.swruntime.tasks.flows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.swruntime.expressions.JQExpression
import com.lemline.swruntime.tasks.NodeInstance
import com.lemline.swruntime.tasks.NodeTask
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.impl.json.JsonUtils

class SetInstance(
    override val node: NodeTask<SetTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SetTask>(node, parent) {
    
    override suspend fun execute() {
        // eval properties
        val data: Map<String, JsonNode> = node.task.set.additionalProperties.mapValues { (_, expr) ->
            // calculate from a string
            if (expr is String) JQExpression.eval(transformedInput!!, expr, scope)
            // calculate from an object
            else JQExpression.eval(transformedInput!!, JsonUtils.fromValue(expr), scope)
        }

        // set raw output
        rawOutput = JsonUtils.`object`().apply {
            data.forEach { set<JsonNode>(it.key, it.value) }
        }
    }

    override fun `continue`() = then()
} 