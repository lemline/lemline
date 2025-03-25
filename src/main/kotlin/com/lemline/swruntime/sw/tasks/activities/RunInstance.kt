package com.lemline.swruntime.sw.tasks.activities

import com.lemline.swruntime.sw.tasks.NodeInstance
import com.lemline.swruntime.sw.tasks.NodeTask
import io.serverlessworkflow.api.types.RunTask

class RunInstance(
    override val node: NodeTask<RunTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RunTask>(node, parent) {
    private var processId: String? = null
    private var status: String? = null
    private var exitCode: Int? = null

    companion object {
        private const val PROCESS_ID = "process.id"
        private const val STATUS = "status"
        private const val EXIT_CODE = "exit.code"
    }
} 