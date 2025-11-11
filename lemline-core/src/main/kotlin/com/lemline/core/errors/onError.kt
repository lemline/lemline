// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors


/**
 *  type: WorkflowErrorType
 *  title: String?,
 *  details: String?
 *  status: Int?
 */
internal typealias OnError = (WorkflowErrorType, String?, String?, Int?) -> Nothing
