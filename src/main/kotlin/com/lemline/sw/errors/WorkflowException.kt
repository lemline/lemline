package com.lemline.sw.errors

import com.lemline.sw.nodes.NodeInstance
import com.lemline.sw.nodes.flows.TryInstance

class WorkflowException(
    val raising: NodeInstance<*>,
    val catching: TryInstance?,
    val error: WorkflowError,
) : RuntimeException()
