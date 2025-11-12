package com.lemline.core.execution

import com.lemline.core.nodes.Node
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.RunWorkflow

enum class ExecutionMode {
    CONTINUOUS,
    TASK_BY_TASK,
    ACTIVITY_BY_ACTIVITY;

    internal fun stopAfterTaskCompletion(node: Node<*>) = when (this) {
        CONTINUOUS -> false
        TASK_BY_TASK -> true
        ACTIVITY_BY_ACTIVITY -> node.isActivity
    }

    internal fun stopIfAsync() = when (this) {
        CONTINUOUS -> false
        TASK_BY_TASK -> true
        ACTIVITY_BY_ACTIVITY -> true
    }

    private val Node<*>.isActivity
        get() = when (task) {
            is CallHTTP -> true
            is RunTask -> when (task.run.get()) {
                is RunWorkflow -> false
                else -> true
            }

            else -> false
        }

}
