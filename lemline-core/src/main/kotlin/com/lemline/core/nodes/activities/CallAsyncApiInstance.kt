// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.activities

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.CallAsyncAPI

class CallAsyncApiInstance(
    override val node: Node<CallAsyncAPI>,
    override val parent: NodeInstance<*>,
) : NodeInstance<CallAsyncAPI>(node, parent) {

}
