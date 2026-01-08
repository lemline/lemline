// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

interface WithOptionalWorkflowInfo {
    val workflowInfo: WorkflowInfo?

    /**
     * Represents the namespace of a workflow, if available.
     */
    val workflowNamespace: WorkflowNamespace? get() = workflowInfo?.namespace

    /**
     * Represents the name of the workflow, if available.
     */
    val workflowName: WorkflowName? get() = workflowInfo?.name

    /**
     * Represents the version of the workflow, if available.
     */
    val workflowVersion: WorkflowVersion? get() = workflowInfo?.version
}

interface WithDefiniteWorkflowInfo : WithOptionalWorkflowInfo {
    override val workflowInfo: WorkflowInfo

    /**
     *  workflowNamespace is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowNamespace: WorkflowNamespace get() = workflowInfo.namespace

    /**
     *  workflowName is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowName: WorkflowName get() = workflowInfo.name

    /**
     *  workflowVersion is redefined here to ensure it is always non-null, overriding a nullable version from WithOptionalWorkflowInfo.
     */
    override val workflowVersion: WorkflowVersion get() = workflowInfo.version
}
