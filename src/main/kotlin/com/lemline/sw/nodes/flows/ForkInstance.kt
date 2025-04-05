package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.ForkTask

class ForkInstance(
    override val node: Node<ForkTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForkTask>(node, parent) {
   
} 