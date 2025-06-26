// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.json.LemlineJson
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.SetTask

class SetInstance(override val node: Node<SetTask>, override val parent: NodeInstance<*>) :
    NodeInstance<SetTask>(node, parent) {

    override suspend fun run() {
        // set raw output
        rawOutput = eval(transformedInput, LemlineJson.encodeToElement(node.task.set))
    }
}
