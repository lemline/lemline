package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.EmitTask

class EmitInstance(
    override val node: Node<EmitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<EmitTask>(node, parent) {
   
} 