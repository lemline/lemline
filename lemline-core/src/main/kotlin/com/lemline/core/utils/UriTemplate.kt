// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import com.lemline.core.nodes.NodeInstance
import io.serverlessworkflow.api.types.UriTemplate
import io.serverlessworkflow.impl.expressions.ExpressionUtils
import java.net.URI

/**
 * Converts a URI template to a string.
 */
internal fun NodeInstance<*>.toUrl(uriTemplate: UriTemplate): String = when (val templateValue = uriTemplate.get()) {
    is URI -> templateValue.toString()
    // TODO literalUriTemplate
    is String -> templateValue
    else -> error(RUNTIME, "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}")
}

/**
 * Converts a URI expression to a string.
 */
internal fun NodeInstance<*>.toUrl(uriExpression: String): String = when (ExpressionUtils.isExpr(uriExpression)) {
    true -> evalString(transformedInput, ExpressionUtils.trimExpr(uriExpression), "URI")
    false -> uriExpression
}
