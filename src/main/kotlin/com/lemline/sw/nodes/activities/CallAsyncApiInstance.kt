package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallAsyncAPI

class CallAsyncApiInstance(
    override val node: Node<CallAsyncAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallAsyncAPI>(node, parent) {
    
} 