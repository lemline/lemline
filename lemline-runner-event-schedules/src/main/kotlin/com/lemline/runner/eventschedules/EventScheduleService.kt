// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.eventschedules

import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.info
import com.lemline.common.logger.logger
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.schemas.SchemaValidator
import com.lemline.core.workflows.WorkflowCache
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
    @Inject lateinit var repository: EventScheduleRepository

    suspend fun handleCloudEvent(event: CloudEvent, namespace: WorkflowNamespace): Int {
        var started = 0
        // Read persisted definitions, not the eventually consistent cache: registrations, updates,
        // and deletions must take effect without waiting for another worker's cache refresh.
        for (candidate in definitions.listAllInNamespace(namespace)) {
            if (WorkflowCache.parseYaml(candidate.definition).schedule?.on == null) continue
            started += databaseConfig.withTransaction { conn ->
                val definition = definitions.findForUpdate(candidate, conn) ?: return@withTransaction 0
                val workflow = WorkflowCache.parseYaml(definition.definition)
                val strategy = workflow.schedule?.on?.get() ?: return@withTransaction 0
                val (stateId, state) = repository.loadState(definition, conn)
                val consumed = state.consume(strategy, event)
                // A matching event needs a receipt even if its termination slot was already filled:
                // otherwise a redelivery could contribute to the next collection after rearming.
                if (!consumed.matched) return@withTransaction 0
                if (!repository.recordEvent(stateId, event, conn)) return@withTransaction 0
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
                repository.saveState(stateId, state, conn)
                accepted
            }
        }
        return started
    }
}
