package com.lemline.core.nodes.activities

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.EmitTask

class EmitInstance(
    override val node: Node<EmitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<EmitTask>(node, parent) {

} 