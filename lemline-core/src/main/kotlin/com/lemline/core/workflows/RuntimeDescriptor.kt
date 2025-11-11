// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.workflows

import com.fasterxml.jackson.databind.JsonNode
import com.lemline.core.workflows.RuntimeDescriptor.metadata
import com.lemline.core.workflows.RuntimeDescriptor.name
import com.lemline.core.workflows.RuntimeDescriptor.version
import kotlinx.serialization.Serializable

/**
 * Data class representing a runtime descriptor.
 *
 * @property name The name of the runtime.
 * @property version The version of the runtime.
 * @property metadata A map containing metadata associated with the runtime.
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#runtime-descriptor">Runtime Descriptor</a>
 */
@Suppress("MemberVisibilityCanBePrivate", "MayBeConstant")
@Serializable
object RuntimeDescriptor {
    val name = "lemline"
    val version = "1.0.0-SNAPSHOT"
    val metadata = mapOf<String, JsonNode>()
}
