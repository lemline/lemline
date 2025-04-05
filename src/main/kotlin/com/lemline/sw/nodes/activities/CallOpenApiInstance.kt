package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallOpenAPI

class CallOpenApiInstance(
    override val node: Node<CallOpenAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallOpenAPI>(node, parent) {
    
} 