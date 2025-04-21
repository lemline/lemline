// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes.flows

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.ForkTask

class ForkInstance(
    override val node: Node<ForkTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<ForkTask>(node, parent) {

}
