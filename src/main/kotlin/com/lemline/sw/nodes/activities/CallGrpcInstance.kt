package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallGRPC

class CallGrpcInstance(
    override val node: Node<CallGRPC>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallGRPC>(node, parent) {
   
} 