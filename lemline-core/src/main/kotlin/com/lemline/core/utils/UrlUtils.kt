// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import com.lemline.core.errors.WorkflowErrorType
import com.lemline.core.errors.WorkflowErrorType.RUNTIME
import io.serverlessworkflow.api.types.UriTemplate
import java.net.URI

/**
 * Converts a UriTemplate to a string URL.
 * This is a standalone utility function used by HttpCall for OAuth URL handling.
 */
internal fun UriTemplate.toUrl(
    onError: (type: WorkflowErrorType, message: String?, details: String?, code: Int?) -> Nothing,
): String = when (val templateValue = get()) {
    is URI -> templateValue.toString()
    is String -> templateValue
    else -> onError(RUNTIME, "Unsupported UriTemplate type: ${templateValue?.javaClass?.name}", null, null)
}
