// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.UriTemplate


/**
 * Get a URL string from an Endpoint
 */
internal fun NodeInstance<*>.toUrl(endpointUnion: Endpoint): String = when (val endpoint = endpointUnion.get()) {
    is UriTemplate -> toUrl(endpoint)

    is EndpointConfiguration -> when (val uriValue = endpoint.uri.get()) {
        is UriTemplate -> toUrl(uriValue)
        is String -> toUrl(uriValue)
        else -> onError(RUNTIME, "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}")
    }

    is String -> toUrl(endpoint)
    else -> onError(RUNTIME, "Unsupported Endpoint type: ${endpoint?.javaClass?.name}")
}
