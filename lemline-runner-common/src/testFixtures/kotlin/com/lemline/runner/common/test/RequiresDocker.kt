// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit 5 annotation to conditionally run tests only when Docker is available.
 * Tests annotated with this will be skipped if Docker is not running.
 *
 * Usage:
 * ```kotlin
 * @RequiresDocker
 * class PostgresRepositoryTest { ... }
 * ```
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DockerCondition::class)
annotation class RequiresDocker

/**
 * JUnit 5 execution condition that checks if Docker is available.
 */
class DockerCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return if (DockerAvailability.isAvailable) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled("Docker is not available, skipping container-based tests")
        }
    }
}
