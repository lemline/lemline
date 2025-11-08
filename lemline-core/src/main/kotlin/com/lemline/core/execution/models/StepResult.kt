// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.lemline.core.execution.models

import com.lemline.core.execution.nodes.NodeInstance
import io.serverlessworkflow.api.types.FlowDirective
import kotlinx.serialization.json.JsonElement

/**
 * Result of a single execution step.
 *
 * This represents the complete state after executing one step of the workflow:
 * - Which node to execute next (or null if workflow complete)
 * - The dataset to pass to the next node
 * - The flow directive indicating navigation intent
 *
 * ## Usage:
 * ```kotlin
 * val result: StepResult = run(currentNode, dataset, flowDirective)
 *
 * when {
 *     result.next == null -> {
 *         // Workflow complete
 *         return result.dataset  // Final output
 *     }
 *     else -> {
 *         // Continue execution
 *         current = result.next
 *         dataset = result.dataset
 *         flowDirective = result.flowDirective
 *     }
 * }
 * ```
 *
 * @property next The next node to execute (null if workflow complete)
 * @property dataset The dataset to pass to the next node
 * @property flowDirective The navigation instruction for the next step (from SDK)
 */
data class StepResult(
    val next: NodeInstance<*>?,
    val dataset: JsonElement,
    val flowDirective: FlowDirective?
)
