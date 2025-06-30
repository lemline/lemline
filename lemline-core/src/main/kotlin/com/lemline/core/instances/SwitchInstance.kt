// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.SwitchItem
import io.serverlessworkflow.api.types.SwitchTask

class SwitchInstance(override val node: Node<SwitchTask>, override val parent: NodeInstance<*>) :
    NodeInstance<SwitchTask>(node, parent) {

    override suspend fun run() {
        this.rawOutput = transformedInput
    }

    override fun then(): NodeInstance<*>? {
        var then: FlowDirective? = null

        // evaluate the different cases
        for (item: SwitchItem in node.task.switch) {
            if ((item.switchCase.`when` == null) || evalCase(item.switchCase.`when`, item.name)) {
                then = item.switchCase.then
                break
            }
        }

        return then(then)
    }

    private fun evalCase(`when`: String, name: String) = evalBoolean(transformedInput, `when`, name)
}
