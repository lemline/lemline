// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.instances

import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import com.lemline.core.utils.toDuration
import io.serverlessworkflow.api.types.CallAsyncAPI
import io.serverlessworkflow.api.types.CallGRPC
import io.serverlessworkflow.api.types.CallHTTP
import io.serverlessworkflow.api.types.CallOpenAPI
import io.serverlessworkflow.api.types.EmitTask
import io.serverlessworkflow.api.types.ListenTask
import io.serverlessworkflow.api.types.RunTask
import io.serverlessworkflow.api.types.TaskBase
import io.serverlessworkflow.api.types.WaitTask

/**
 * Sealed base class for all activity node instances.
 * This class provides a default implementation for the `run()` method
 * by delegating to the `ActivityRunner`, ensuring compile-time exhaustiveness
 * for all known activity types.
 *
 * The specific activity instance classes are defined in this same file to reduce
 * file clutter, as they are simple type markers for the sealed hierarchy.
 */
sealed class ActivityInstance<T : TaskBase>(
    node: Node<T>,
    parent: NodeInstance<*>?,
) : NodeInstance<T>(node, parent) {
    final override suspend fun run() {
        rootInstance.activityRunnerProvider.run(this)
    }
}

// --- Concrete Activity Instance Definitions ---

class CallHttpInstance(node: Node<CallHTTP>, parent: NodeInstance<*>) :
    ActivityInstance<CallHTTP>(node, parent)

class CallGrpcInstance(node: Node<CallGRPC>, parent: NodeInstance<*>) :
    ActivityInstance<CallGRPC>(node, parent)

class CallAsyncApiInstance(node: Node<CallAsyncAPI>, parent: NodeInstance<*>) :
    ActivityInstance<CallAsyncAPI>(node, parent)

class CallOpenApiInstance(node: Node<CallOpenAPI>, parent: NodeInstance<*>) :
    ActivityInstance<CallOpenAPI>(node, parent)

class EmitInstance(node: Node<EmitTask>, parent: NodeInstance<*>) :
    ActivityInstance<EmitTask>(node, parent)

class ListenInstance(node: Node<ListenTask>, parent: NodeInstance<*>) :
    ActivityInstance<ListenTask>(node, parent)

class RunInstance(node: Node<RunTask>, parent: NodeInstance<*>) :
    ActivityInstance<RunTask>(node, parent)

class WaitInstance(node: Node<WaitTask>, parent: NodeInstance<*>) :
    ActivityInstance<WaitTask>(node, parent) {
    /**
     * Duration for which the workflow should wait before resuming.
     * The duration is extracted from the WaitTask and converted to a Duration using ISO-8601 duration format.
     * Examples: "PT15S" (15 seconds), "PT1H" (1 hour), "P1D" (1 day) */
    val delay by lazy { node.task.wait.toDuration() }
}
