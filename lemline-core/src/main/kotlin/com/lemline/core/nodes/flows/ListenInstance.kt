package com.lemline.core.nodes.flows

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.ListenTask

class ListenInstance(
    override val node: Node<ListenTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ListenTask>(node, parent) {

} 