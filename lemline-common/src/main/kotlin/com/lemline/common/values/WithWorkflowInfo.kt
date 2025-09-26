// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

interface WithOptionalWorkflowInfo {
    val workflowInfo: WorkflowInfo?

    /**
     * Represents the unique identifier of a workflow, if available.
     */
    val workflowId: WorkflowId? get() = workflowInfo?.workflowId

    /**
     * Represents the namespace of a workflow, if available.
     */
    val workflowNamespace: WorkflowNamespace? get() = workflowInfo?.workflowNamespace

    /**
     * Represents the name of the workflow, if available.
     */
    val workflowName: WorkflowName? get() = workflowInfo?.workflowName

    /**
     * Represents the version of the workflow, if available.
     */
    val workflowVersion: WorkflowVersion? get() = workflowInfo?.workflowVersion
}

interface WithDefiniteWorkflowInfo : WithOptionalWorkflowInfo {
    override val workflowInfo: WorkflowInfo

    /**
     *  workflowId is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowId: WorkflowId get() = workflowInfo.workflowId

    /**
     *  workflowNamespace is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowNamespace: WorkflowNamespace get() = workflowInfo.workflowNamespace

    /**
     *  workflowName is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowName: WorkflowName get() = workflowInfo.workflowName

    /**
     *  workflowVersion is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowVersion: WorkflowVersion get() = workflowInfo.workflowVersion
}
