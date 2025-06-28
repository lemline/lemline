// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import io.serverlessworkflow.api.types.RunTaskConfiguration
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.ALL
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.CODE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.NONE
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDERR
import io.serverlessworkflow.api.types.RunTaskConfiguration.ProcessReturnType.STDOUT
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ProcessResult(
    val code: Int,
    val stdout: String,
    val stderr: String
) {
    fun get(returnType: RunTaskConfiguration.ProcessReturnType): JsonElement = when (returnType) {
        STDOUT -> JsonPrimitive(stdout)
        STDERR -> JsonPrimitive(stderr)
        CODE -> JsonPrimitive(code)
        ALL -> JsonObject(
            mapOf(
                "code" to JsonPrimitive(code),
                "stdout" to JsonPrimitive(stdout),
                "stderr" to JsonPrimitive(stderr)
            )
        )

        NONE -> JsonNull
    }
}
