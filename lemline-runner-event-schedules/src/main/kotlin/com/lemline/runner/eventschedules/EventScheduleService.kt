// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.info
import com.lemline.common.logger.logger
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.workflows.WorkflowCache
import com.lemline.runner.listeners.DefinitionListenService
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.definitions.DefinitionRepository
import io.cloudevents.CloudEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class EventScheduleService {
    private val logger = logger()
    @Inject lateinit var databaseConfig: DatabaseConfig
    @Inject lateinit var definitions: DefinitionRepository
    @Inject lateinit var definitionListenService: DefinitionListenService
    @Inject lateinit var repository: EventScheduleRepository

    suspend fun handleCloudEvent(event: CloudEvent, namespace: WorkflowNamespace): Int {
        var started = 0
        for (candidate in definitionListenService.findMatchingEventSchedules(event, namespace)) {
            started += databaseConfig.withTransaction { conn ->
                val definition = definitions.findForUpdate(candidate.workflowInfo, conn) ?: run {
                    WorkflowCache.removeEventSchedule(candidate)
                    return@withTransaction 0
                }
                val workflow = WorkflowCache.refreshDefinition(candidate.workflowInfo, definition.definition)
                val strategy = WorkflowCache.getEventConsumption(workflow) ?: return@withTransaction 0
                val stateId = repository.loadState(definition, event, conn) ?: return@withTransaction 0
                val consumed = EventScheduleCollector(repository).consume(stateId, strategy, event, conn)
                // A matching event needs a receipt even if its termination slot was already filled:
                // otherwise a redelivery could contribute to the next collection after rearming.
                if (!consumed.matched) return@withTransaction 0
                repository.recordEvent(stateId, event, conn)
                var accepted = 0
                for (input in consumed.inputs) {
                    try {
                        workflow.input?.schema?.let { SchemaValidator.validate(input, it) }
                    } catch (e: IllegalArgumentException) {
                        // Invalid trigger input is consumed, not retried forever by the broker.
                        // Keep its receipt and rearm the collection; do not execute workflow actions.
                        logger.warn { "Rejected event schedule input for ${workflow.info}: ${e.message}" }
                        continue
                    }
                    repository.insert(
                        EventScheduleModel(instanceMessage = InstanceMessage(
                            workflowInfo = workflow.info,
                            workflowState = StepByStepOrchestrator.initCmd(WorkflowId.random(), input, false),
                        )), conn
                    )
                    accepted++
                }
                accepted
            }
        }
        return started
    }
}
