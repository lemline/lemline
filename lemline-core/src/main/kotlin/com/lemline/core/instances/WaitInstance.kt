// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.utils.toDuration
import io.serverlessworkflow.api.types.WaitTask

class WaitInstance(override val node: Node<WaitTask>, override val parent: NodeInstance<*>) :
    NodeInstance<WaitTask>(node, parent) {

    /**
     * Duration for which the workflow should wait before resuming.
     * The duration is extracted from the WaitTask and converted to a Duration using ISO-8601 duration format.
     * Examples: "PT15S" (15 seconds), "PT1H" (1 hour), "P1D" (1 day) */
    val delay by lazy { node.task.wait.toDuration() }
}
