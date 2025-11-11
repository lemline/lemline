// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Exception indicating that a child workflow should be started.
 *
 * This exception serves as a marker to indicate the initiation of a child workflow
 * during a larger workflow process. It carries configuration details associated
 * with the child workflow, encapsulated within the [ChildWorkflowConfig] instance.
 */
class ChildWorkflowRequestedException(
    val output: JsonElement? = null,
    val childWorkflowConfig: ChildWorkflowConfig
) : RuntimeException()

/**
 * Configuration details required to initiate a child workflow.
 *
 * This data class encapsulates the metadata and input parameters needed to
 * start a child workflow instance within a larger workflow process.
 *
 * @property namespace The namespace of the child workflow, used to scope workflows within an environment.
 * @property name The name of the child workflow to be executed.
 * @property version The version of the child workflow.
 * @property input Serialized JSON input provided to the child workflow.
 * @property awaitCompletion Indicates whether the parent workflow should wait for the child workflow to complete.
 */
@Serializable
data class ChildWorkflowConfig(
    val namespace: String,
    val name: String,
    val version: String,
    val input: JsonElement,
    val awaitCompletion: Boolean
)
