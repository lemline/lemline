package com.lemline.sw.nodes.activities

import com.lemline.sw.nodes.Node
import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.utils.toDuration
import io.serverlessworkflow.api.types.WaitTask

class WaitInstance(
    override val node: Node<WaitTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<WaitTask>(node, parent) {

    /**
     * Duration for which the workflow should wait before resuming.
     * The duration is extracted from the WaitTask and converted to a Duration using ISO-8601 duration format.
     * Examples: "PT15S" (15 seconds), "PT1H" (1 hour), "P1D" (1 day) */
    val delay by lazy { node.task.wait.toDuration() }

} 