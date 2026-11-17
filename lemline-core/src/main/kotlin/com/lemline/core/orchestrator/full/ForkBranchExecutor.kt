// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.orchestrator.full

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.lemline.common.json.*
import com.lemline.common.values.info
import com.lemline.core.activities.ActivityExecutor
import com.lemline.core.cloudevents.CloudEventHook
import com.lemline.core.functions.FunctionResolver
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.nodes.Node
import com.lemline.core.states.NodeStack
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.utils.mapAwaitAllFailFast
import com.lemline.core.utils.mapAwaitFirstFailSlow
import io.serverlessworkflow.api.types.Workflow
import kotlin.time.Clock

/**
 * Executes fork branches in compete or cooperative mode.
 */
internal object ForkBranchExecutor {

    /**
     * Execute fork branches in compete mode (race for first completion).
     *
     * Returns the output from the first branch to complete successfully.
     * Throws an exception if all branches fail.
     */
    suspend fun executeCompete(
        workflow: Workflow,
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonNode,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
        resumeFn: suspend (Workflow, WorkflowCommand, Boolean, ActivityExecutor, FunctionResolver, CloudEventHook, LifecycleEventHook) -> WorkflowEvent.Outcome
    ): JsonNode {
        return branches.mapAwaitFirstFailSlow { branchNode ->
            lifecycleHook.onTaskCreated(
                workflowInfo = workflow.info,
                nodeStack = nodeStack,
                nodePosition = branchNode.position,
                input = rawInput,
                createdAt = Clock.System.now(),
            )

            resumeFn(
                workflow,
                WorkflowCommand.ResumeFromTask(
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    rawInput = rawInput,
                    flowDirective = null
                ),
                serde,
                activityExecutor,
                functionResolver,
                cloudEventHook,
                lifecycleHook,
            ).value()
        }
    }

    /**
     * Execute fork branches in cooperative mode (wait for all).
     *
     * Returns an array containing outputs from all branches.
     * Throws an exception for the first branch failing.
     */
    suspend fun executeCooperative(
        workflow: Workflow,
        nodeStack: NodeStack,
        branches: List<Node<*>>,
        rawInput: JsonNode,
        serde: Boolean,
        activityExecutor: ActivityExecutor,
        functionResolver: FunctionResolver,
        cloudEventHook: CloudEventHook,
        lifecycleHook: LifecycleEventHook,
        resumeFn: suspend (Workflow, WorkflowCommand, Boolean, ActivityExecutor, FunctionResolver, CloudEventHook, LifecycleEventHook) -> WorkflowEvent.Outcome
    ): ArrayNode {
        return ArrayNode(
            branches.mapAwaitAllFailFast { branchNode ->
                lifecycleHook.onTaskCreated(
                    workflowInfo = workflow.info,
                    nodeStack = nodeStack,
                    nodePosition = branchNode.position,
                    input = rawInput,
                    createdAt = Clock.System.now(),
                )

                resumeFn(
                    workflow,
                    WorkflowCommand.ResumeFromTask(
                        nodeStack = nodeStack,
                        nodePosition = branchNode.position,
                        rawInput = rawInput,
                        flowDirective = null
                    ),
                    serde,
                    activityExecutor,
                    functionResolver,
                    cloudEventHook,
                    lifecycleHook,
                ).value()
            })
    }
}
