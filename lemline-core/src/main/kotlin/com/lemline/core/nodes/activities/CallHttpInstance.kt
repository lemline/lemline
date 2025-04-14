package com.lemline.core.nodes.activities

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallHTTP

class CallHttpInstance(
    override val node: Node<CallHTTP>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallHTTP>(node, parent) {

} 