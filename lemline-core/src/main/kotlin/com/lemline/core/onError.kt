// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core

import com.lemline.core.errors.WorkflowErrorType

/**
 *  type: WorkflowErrorType
 *  title: String?,
 *  details: String?
 *  status: Int?
 */
internal typealias OnError = (WorkflowErrorType, String?, String?, Int?) -> Nothing
