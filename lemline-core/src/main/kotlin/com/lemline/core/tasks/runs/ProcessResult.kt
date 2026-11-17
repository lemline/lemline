// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.tasks.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lemline.common.json.*
import io.serverlessworkflow.api.types.RunTaskConfiguration
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT

data class ProcessResult(
    val code: Int,
    val stdout: String,
    val stderr: String
) {
    fun get(returnType: RunTaskConfiguration.ProcessReturnType): JsonNode = when (returnType) {
        STDOUT -> jsonValue(stdout)
        STDERR -> jsonValue(stderr)
        CODE -> jsonValue(code)
        ALL -> ObjectNode(
            mapOf(
                "code" to jsonValue(code),
                "stdout" to jsonValue(stdout),
                "stderr" to jsonValue(stderr)
            )
        )

        NONE -> NullNode.instance
    }
}
