package com.lemline.sw.nodes.flows

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: Node<ListenTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ListenTask>(node, parent) {
   
} 