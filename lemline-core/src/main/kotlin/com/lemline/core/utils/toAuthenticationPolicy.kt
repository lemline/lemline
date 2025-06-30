// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.AuthenticationPolicy
import io.serverlessworkflow.api.types.AuthenticationPolicyReference
import io.serverlessworkflow.api.types.AuthenticationPolicyUnion
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.UriTemplate

/**
 * Resolves an authentication policy based on the provided name.
 *
 * This function retrieves an authentication policy from the workflow's configuration
 * using the specified name. If the name corresponds to an authentication policy,
 * it returns the policy; otherwise, it returns null.
 *
 * @param name The name of the authentication policy to retrieve.
 * @return The resolved AuthenticationPolicy or null if not found.
 */
internal fun NodeInstance<*>.getAuthenticationPolicyByName(name: String): AuthenticationPolicy =
    rootInstance.node.task.use?.authentications?.additionalProperties?.get(name)?.get()
        ?: onError(CONFIGURATION, "Named authentification not found: $name", null, null)

/**
 * Extracts authentication information from the endpoint configuration if available.
 * Handles both direct EndpointConfiguration objects and named reference.
 */
internal fun NodeInstance<*>.toAuthenticationPolicy(endpointUnion: Endpoint): AuthenticationPolicy? {
    val authPolicyUnion = when (val endpoint = endpointUnion.get()) {
        // EndpointConfiguration object
        is EndpointConfiguration -> endpoint.authentication
        // UriTemplate
        is UriTemplate -> null
        // Expression
        is String -> null
        // Direct String or another unsupported type
        else -> onError(RUNTIME, "Unsupported EndPoint type: ${endpoint.javaClass.name}")
    }

    return when (val authPolicy = authPolicyUnion?.get()) {
        null -> null
        is AuthenticationPolicyReference -> getAuthenticationPolicyByName(authPolicy.use)
        is AuthenticationPolicyUnion -> authPolicy.get()
        else -> onError(RUNTIME, "Unsupported AuthenticationPolicy type: ${authPolicy.javaClass.name}")
    }
}
