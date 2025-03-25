package com.lemline.swruntime.sw.tasks.flows

import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.FlowDirective
import io.serverlessworkflow.api.types.SwitchItem
import io.serverlessworkflow.api.types.SwitchTask

class SwitchInstance(
    override val node: NodeTask<SwitchTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<SwitchTask>(node, parent) {

    override fun `continue`(): NodeInstance<*>? {
        var then: FlowDirective? = null

        // evaluate the different cases
        for (item: SwitchItem in node.task.switch) {
            if ((item.switchCase.`when` == null) || evalCase(item.switchCase.`when`, item.name)) {
                then = item.switchCase.then
                break
            }
        }

        return then(then?.get())
    }

    private fun evalCase(`when`: String, name: String): Boolean {
        val out = eval(transformedInput!!, `when`)
        return if (out.isBoolean) out.asBoolean() else error("in the '$name' case, '.when' condition must be a boolean, but is $out")
    }
} 