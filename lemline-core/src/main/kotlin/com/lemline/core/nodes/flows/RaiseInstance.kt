package com.lemline.core.nodes.flows

import com.lemline.core.errors.WorkflowError
import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.Node
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.Error
import io.serverlessworkflow.api.types.RaiseTask
import io.serverlessworkflow.api.types.RaiseTaskError
import io.serverlessworkflow.api.types.UriTemplate
import java.net.URI

class RaiseInstance(
    override val node: Node<RaiseTask>,
    override val parent: NodeInstance<*>,
) : NodeInstance<RaiseTask>(node, parent) {

    private val error by lazy { node.task.raise.error.getError() }

    override suspend fun execute() {

        val error = WorkflowError(
            type = error.getErrorType(),
            status = error.status,
            instance = node.position.jsonPointer.toString(),
            title = error.title,
            details = error.detail,
        )

        raise(error)
    }

    private fun Error.getErrorType() = when (val errorType = type.get()) {
        is UriTemplate -> errorType.getErrorType() // TODO interpret URI
        is String -> errorType
        else -> error(RUNTIME, "Unknown Error type '$errorType'")
    }

    private fun UriTemplate.getErrorType() = when (val errorType = get()) {
        is URI -> errorType.toString()
        is String -> errorType
        else -> error(RUNTIME, "Unknown Uri Template '$errorType'")
    }

    private fun RaiseTaskError.getError(): Error = when (val error = get()) {
        is String -> rootInstance.node.task.use?.errors?.additionalProperties?.get(error)
            ?: error(CONFIGURATION, "Error '$error' not found")

        is Error -> error
        else -> error(RUNTIME, "Unknown Error '$error'")
    }
} 