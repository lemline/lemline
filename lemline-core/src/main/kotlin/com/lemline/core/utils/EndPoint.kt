package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.EXPRESSION
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.Endpoint
import io.serverlessworkflow.api.types.EndpointConfiguration
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI

internal fun NodeInstance<*>.toUrl(endpoint: Endpoint): String = when (val value = endpoint.get()) {
    is UriTemplate -> {
        when (val templateValue = value.get()) {
            is URI -> templateValue.toString()
            is String -> templateValue
            else -> error(EXPRESSION, "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}")
        }
    }

    is EndpointConfiguration -> {
        val uri = value.uri
        when (val uriValue = uri.get()) {
            is UriTemplate -> {
                when (val templateValue = uriValue.get()) {
                    is URI -> templateValue.toString()
                    is String -> templateValue
                    else -> error(EXPRESSION, "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}")
                }
            }

            is String -> uriValue
            else -> error(EXPRESSION, "Unsupported EndpointUri type: ${uriValue?.javaClass?.name}")
        }
    }

    is String -> when (ExpressionUtils.isExpr(value)) {
        true -> evalString(transformedInput, ExpressionUtils.trimExpr(value), "EndPoint")
        false -> value
    }

    else -> error(EXPRESSION, "Unsupported Endpoint type: ${value?.javaClass?.name}")
}