// SPDX-License-Identifier: BUSL-1.1
@file:Suppress("unused")

package com.lemline.common

import java.io.IOException
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DockerAvailableCondition::class)
annotation class EnabledOnlyIfDockerAvailable

class DockerAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return if (isDockerAvailable()) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled("Docker is not available")
        }
    }

    private fun isDockerAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("docker", "info").start()
            process.waitFor() == 0
        } catch (_: IOException) {
            false
        }
    }
}
