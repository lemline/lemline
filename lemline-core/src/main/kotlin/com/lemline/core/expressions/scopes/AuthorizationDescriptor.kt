package com.lemline.core.expressions.scopes

import kotlinx.serialization.Serializable

/**
 * Data class representing an authorization descriptor.
 *
 * @property scheme The authorization scheme (e.g., "Bearer").
 * @property parameter The authorization parameter (e.g., token or credentials).
 *
 * @see <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl.md#authorization-descriptor">Authorization Descriptor</a>
 */
@Serializable
data class AuthorizationDescriptor(
    val scheme: String,
    val parameter: String,
)