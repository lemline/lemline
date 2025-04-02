package com.lemline.sw.nodes.flows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.impl.json.JsonUtils

class SetInstance(
    override val node: Node<SetTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SetTask>(node, parent) {

    override suspend fun execute() {
        // eval properties
        val data: Map<String, JsonNode> = node.task.set.additionalProperties.mapValues { (_, expr) ->
            // calculate from a string
            if (expr is String) eval(transformedInput!!, expr)
            // calculate from an object
            else eval(transformedInput!!, JsonUtils.fromValue(expr))
        }

        // set raw output
        rawOutput = JsonUtils.`object`().apply {
            data.forEach { set<JsonNode>(it.key, it.value) }
        }
    }
}