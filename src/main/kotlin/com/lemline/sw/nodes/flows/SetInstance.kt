package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.SetTask
import io.serverlessworkflow.impl.json.JsonUtils

class SetInstance(
    override val node: Node<SetTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SetTask>(node, parent) {

    override suspend fun execute() {
        val setObject = JsonUtils.fromValue(node.task.set.additionalProperties)

        // set raw output
        rawOutput = eval(transformedInput, setObject)
    }
}