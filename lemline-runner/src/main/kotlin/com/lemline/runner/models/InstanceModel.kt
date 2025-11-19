// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
sealed interface InstanceModel : WithId, WithOptionalWorkflowInfo {
    /** The current execution state of the workflow instance, null if not available */
    val workflowState: WorkflowState?
}
