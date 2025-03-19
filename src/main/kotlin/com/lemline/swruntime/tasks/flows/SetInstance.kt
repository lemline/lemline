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

    private val variables: Map<String, JsonNode> by lazy {
        // for all additional properties
        node.task.set.additionalProperties.mapValues { (_, expr) ->
            // calculate from a string
            if (expr is String) JQExpression.eval(transformedInput!!, expr, scope)
            // calculate from an object
            else JQExpression.eval(transformedInput!!, JsonUtils.fromValue(expr), scope)
        }
    }

    override suspend fun execute() {
        // add additional property to the parent scope
        variables.forEach { parent.customScope.set<JsonNode>(it.key, it.value) }
    }
} 