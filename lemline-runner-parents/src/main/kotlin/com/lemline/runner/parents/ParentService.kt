// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.parents

import com.lemline.common.logger.logger
import com.lemline.common.values.WorkflowId
import com.lemline.core.errors.InternalException
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.states.WorkflowEvent
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Service for handling parent-child workflow relationships.
 *
 * Provides business logic for:
 * - Starting child workflows (creating parent record, preparing child, emitting child message)
 * - Resuming parent workflows when children complete or fail
 */
@ExperimentalTime
@ExperimentalSerializationApi
@ApplicationScoped
class ParentService {

    @Inject
    lateinit var parentRepository: ParentRepository

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var starter: Starter

    @Inject
    lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var lifecycleHook: LifecycleEventHook

    private val logger = logger()

    /**
     * Handles run workflow started by:
     * 1. Creating parent record with child workflow ID
     * 2. Preparing child workflow (loading definition, validating input)
     * 3. Inserting schedule if present
     * 4. Emitting child workflow message
     *
     * @param instance The instance message containing the run workflow started event
     * @return true if the parent was created, false if it already existed (idempotent)
     */
    suspend fun handleRunWorkflowStarted(instance: InstanceMessage<WorkflowEvent.RunWorkflowStarted>) {
        // Derive parent model
        val parent = ParentModel(instanceMessage = instance)

        return databaseConfig.withTransaction { conn ->

            // Insert parent with child_id
            val rowsInserted = parentRepository.insert(
                entity = parent,
                connection = conn
            )
            if (rowsInserted == 0) {
                logger.warn { "Parent model already exists (idempotent insert): $parent" }
                return@withTransaction
            }

            // Create the child + optional schedule
            val preparedWorkflow = starter.prepareWorkflow(
                workflowId = parent.childId,
                workflowNamespace = instance.workflowState.config.namespace,
                workflowName = instance.workflowState.config.name,
                optionalVersion = instance.workflowState.config.version,
                workflowInput = instance.workflowState.config.input,
                hasWaitingParent = instance.workflowState.config.sync, // <= true only for sync child
                zoneId = null
            ) { error(it) }

            // Insert schedule if present
            preparedWorkflow.scheduleModel?.let { scheduleRepository.insert(it, conn) }

            // Emit child to the workflow channel with idempotent message ID
            preparedWorkflow.instanceMessage?.let { child ->
                commandEmitter.send(child, parent.childId.value)

                // Emit workflow.created lifecycle event for the child workflow
                preparedWorkflow.onWorkflowCreated(lifecycleHook)
            }
        }
    }

    /**
     * Resumes a parent workflow when its child workflow completes successfully.
     *
     * @param childWorkflowId The workflow ID of the completed child
     * @param childOutput The output from the child workflow
     * @param connection Optional database connection for transaction participation
     * @return true if parent was resumed, false if already processed (idempotent)
     */
    suspend fun resumeParentOnChildCompletion(
        childWorkflowId: WorkflowId,
        childOutput: kotlinx.serialization.json.JsonElement,
        connection: Connection
    ): Boolean {
        val parent = parentRepository.findByChildId(childWorkflowId, connection)
            ?: error("CRITICAL - Unable to find parent for child $childWorkflowId")

        // Check if already processed (idempotent handling)
        if (parent.completedAt != null) {
            logger.info { "Parent ${parent.id} already resumed for child $childWorkflowId (idempotent), skipping" }
            return false
        }

        // Parent state
        val state = parent.instanceMessage.workflowState

        // Derive resume message ID from parent model ID
        val resumeMessageId = parent.id.derive("-resume-completed")

        // Restart parent with child output
        commandEmitter.send(
            InstanceMessage(
                workflowInfo = parent.instanceMessage.workflowInfo,
                workflowState = state.resumeAsCompleted(childOutput),
            ),
            resumeMessageId
        )

        // Mark parent as completed for cleanup (event-driven state - processed once)
        val now = Clock.System.now()
        parent.completedAt = now
        parent.cleanupAfter = now
        parentRepository.update(parent, connection)

        logger.debug {
            "Parent workflow $parent resumed after child $childWorkflowId completion"
        }

        return true
    }

    /**
     * Resumes a parent workflow when its child workflow fails.
     *
     * @param childWorkflowId The workflow ID of the failed child
     * @param childError The error from the child workflow
     * @param connection Optional database connection for transaction participation
     * @return true if parent was resumed, false if already processed (idempotent)
     */
    suspend fun resumeParentOnChildFailure(
        childWorkflowId: WorkflowId,
        childError: InternalException.Error,
        connection: Connection
    ): Boolean {
        val parent = parentRepository.findByChildId(childWorkflowId, connection)
            ?: error("CRITICAL - Unable to find parent for child $childWorkflowId")

        // Check if already processed (defense in depth)
        if (parent.completedAt != null) {
            logger.info { "Parent ${parent.id} already resumed for child $childWorkflowId (idempotent), skipping" }
            return false
        }

        // Parent state
        val state = parent.instanceMessage.workflowState

        // Derive resume message ID from parent model ID
        val resumeMessageId = parent.id.derive("-resume-failed")

        // Restart parent with error
        commandEmitter.send(
            InstanceMessage(
                workflowInfo = parent.instanceMessage.workflowInfo,
                workflowState = state.resumeAsFailed(childError),
            ),
            resumeMessageId
        )

        // Mark parent as completed for cleanup (event-driven state - processed once)
        val now = Clock.System.now()
        parent.completedAt = now
        parent.cleanupAfter = now
        parentRepository.update(parent, connection)

        logger.debug {
            "Parent workflow $parent resumed after child $childWorkflowId failure"
        }

        return true
    }
}
