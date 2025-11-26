// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.states

import com.lemline.common.values.NodePosition
import com.lemline.common.values.WorkflowStep

/**
 * Builds a dynamic WorkflowStep by walking up the node tree from current position,
 * collecting node names and visit counts from the state map.
 *
 * The WorkflowStep extends NodePosition with visit counts to create unique identifiers
 * for each execution instance, preventing database ID collisions when the same node
 * is visited multiple times (loops, retries, parallel branches).
 *
 * ## How It Works
 *
 * 1. Starts at the current NodePosition
 * 2. Walks up the tree to root, collecting each node's name and visitCount
 * 3. Builds path segments in format: "{nodeName},{visitCount}"
 * 4. Creates WorkflowStep with complete path
 *
 * ## Example
 *
 * Given workflow execution state:
 * ```
 * Current position: /for/do/processItem
 * taskStates = {
 *   /for -> ForState(visitCount=2, ...),
 *   /for/do -> DoState(visitCount=1, ...),
 *   /for/do/processItem -> SetState(visitCount=0, ...)
 * }
 * ```
 *
 * Produces: WorkflowStep("/for,2/do,1/processItem,0")
 *
 * This uniquely identifies the 3rd iteration (visitCount=2) of the for loop,
 * 2nd task (visitCount=1) in the do block, and 1st execution (visitCount=0)
 * of the processItem task.
 */
object WorkflowStepBuilder {

    /**
     * Builds a WorkflowStep from current position and task states.
     *
     * @param currentPosition The current NodePosition in the workflow tree
     * @param taskStates Map of NodePosition to TaskState containing visit counts
     * @return WorkflowStep with visit counts for unique identification
     */
    fun buildWorkflowStep(
        currentPosition: NodePosition,
        taskStates: Map<NodePosition, TaskState>
    ): WorkflowStep {
        val segments = mutableListOf<String>()

        // Walk up from current position to root
        var pos: NodePosition? = currentPosition
        while (pos != null && pos != NodePosition.root) {
            // Look up state using STATIC position key
            val state = taskStates[pos]
            val visitCount = state?.visitCount ?: 0

            // Add name,count pair as single segment
            segments.add(0, "${pos.nodeName},$visitCount")

            pos = pos.parent
        }

        // Build WorkflowStep path
        return if (segments.isEmpty()) {
            // Root position - special case
            WorkflowStep("/root,0")
        } else {
            WorkflowStep("/" + segments.joinToString("/"))
        }
    }
}
