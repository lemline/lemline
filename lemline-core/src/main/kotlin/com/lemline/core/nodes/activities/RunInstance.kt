package com.lemline.core.nodes.activities

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.RunTask

class RunInstance(
    override val node: Node<RunTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RunTask>(node, parent) {

} 