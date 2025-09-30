// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models.bases

import com.lemline.common.values.WorkflowInfo
import com.lemline.runner.repositories.capabilities.InfoColumns
import com.lemline.runner.repositories.capabilities.OptionalInfoColumns
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface WithWorkflowInfo : InfoColumns {

    val workflowInfo: WorkflowInfo

    override val workflowId get() = workflowInfo.workflowId

    override val workflowName get() = workflowInfo.workflowName

    override val workflowNamespace get() = workflowInfo.workflowNamespace

    override val workflowVersion get() = workflowInfo.workflowVersion
}

@ExperimentalTime
interface WithOptionalWorkflowInfo : OptionalInfoColumns {

    val workflowInfo: WorkflowInfo?

    override val workflowId get() = workflowInfo?.workflowId

    override val workflowName get() = workflowInfo?.workflowName

    override val workflowNamespace get() = workflowInfo?.workflowNamespace

    override val workflowVersion get() = workflowInfo?.workflowVersion
}
