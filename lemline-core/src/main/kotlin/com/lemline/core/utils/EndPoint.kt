// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.CONFIGURATION
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.*

/**
 * Get a URL string from an Endpoint
 */
internal fun NodeInstance<*>.toUrl(endpointUnion: Endpoint): String = when (val endpoint = endpointUnion.get()) {
    is UriTemplate -> toUrl(endpoint)

    is EndpointConfiguration -> when (val uriValue = endpoint.uri.get()) {
        is UriTemplate -> toUrl(uriValue)
        is String -> toUrl(uriValue)
        else -> error(RUNTIME, "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}")
    }

    is String -> toUrl(endpoint)
    else -> error(RUNTIME, "Unsupported Endpoint type: ${endpoint?.javaClass?.name}")
}

/**
 * Extracts authentication information from the endpoint configuration if available.
 * Handles both direct EndpointConfiguration objects and named reference.
 */
internal fun NodeInstance<*>.toAuthenticationPolicy(endpointUnion: Endpoint): AuthenticationPolicy? {
    val authPolicyUnion: ReferenceableAuthenticationPolicy? = when (val endpoint = endpointUnion.get()) {
        // EndpointConfiguration object
        is EndpointConfiguration -> endpoint.authentication
        // Direct String or another unsupported type
        else -> null
    }

    return when (val authPolicy = authPolicyUnion?.get()) {
        null -> null
        is AuthenticationPolicyReference ->
            rootInstance.node.task.use?.authentications?.additionalProperties?.get(authPolicy.use)
                ?: error(CONFIGURATION, "Named authentification not found: ${authPolicy.use}")

        is AuthenticationPolicyUnion -> authPolicy

        else -> error(RUNTIME, "Unsupported AuthenticationPolicy type: ${authPolicy.javaClass.name}")
    }?.get()
}
