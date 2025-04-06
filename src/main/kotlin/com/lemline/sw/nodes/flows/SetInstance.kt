package com.lemline.sw.nodes.flows

import com.lemline.common.json.Json
import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.SetTask

class SetInstance(
    override val node: Node<SetTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SetTask>(node, parent) {

    override suspend fun execute() {
        // set raw output
        rawOutput = eval(transformedInput, Json.encodeToElement(node.task.set))
    }
}