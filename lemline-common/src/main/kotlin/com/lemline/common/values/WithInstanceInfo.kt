// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

interface WithInstanceInfo {
    val workflowInfo: WorkflowInfo?

    val workflowId: WorkflowId? get() = workflowInfo?.workflowId

    val workflowNamespace: WorkflowNamespace? get() = workflowInfo?.workflowNamespace

    val workflowName: WorkflowName? get() = workflowInfo?.workflowName

    val workflowVersion: WorkflowVersion? get() = workflowInfo?.workflowVersion
}
