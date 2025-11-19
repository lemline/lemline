// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.WithOptionalWorkflowInfo
import com.lemline.core.states.WorkflowState
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

@ExperimentalSerializationApi
@ExperimentalTime
sealed interface InstanceModel : WithId, WithOptionalWorkflowInfo {
    val workflowState: WorkflowState?
}
