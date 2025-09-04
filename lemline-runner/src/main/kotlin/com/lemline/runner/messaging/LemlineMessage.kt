package com.lemline.runner.messaging

import com.lemline.core.workflows.WorkflowState
import com.lemline.runner.models.IDV7
import kotlin.time.ExperimentalTime

@ExperimentalTime
interface LemlineMessage : JsonSerializable {

    val workflowState: WorkflowState?

    /**
     * The ID of the parent's model, if any.
     */
    val parentId: IDV7?
}
