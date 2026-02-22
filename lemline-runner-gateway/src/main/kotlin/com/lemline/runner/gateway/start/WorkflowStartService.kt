// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.start

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.gateway.v1.StartWorkflowRequest
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxModel
import com.lemline.runner.gateway.outbox.GatewayCommandOutboxRepository
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.Clock
import kotlinx.serialization.json.JsonElement

enum class GatewayStartResult {
    ACCEPTED_NEW,
    ACCEPTED_EXISTING,
}

data class WorkflowStartResult(
    val workflowId: WorkflowId,
    val result: GatewayStartResult,
)

@ApplicationScoped
class WorkflowStartService {

    @Inject
    lateinit var starter: Starter

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var gatewayOutboxRepository: GatewayCommandOutboxRepository

    @Inject
    lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    suspend fun start(request: StartWorkflowRequest, principal: GatewayPrincipal): WorkflowStartResult {
        val workflowId = parseWorkflowId(request.workflowId)
        val workflowNamespace = parseWorkflowNamespace(request.namespace)
        val workflowName = parseWorkflowName(request.name)
        val workflowVersion = parseWorkflowVersion(request.version)
        val zoneId = parseZoneId(request)

        authorizer.requireScope(principal, "lemline.start")
        authorizer.requireNamespace(principal, workflowNamespace.toString())

        val workflowInput = parseInputJson(request)
        val workflowInfo = WorkflowInfo(
            namespace = workflowNamespace,
            name = workflowName,
            version = workflowVersion
        )

        // Prepare workflow (validates definition exists, builds messages)
        val preparedWorkflow = starter.prepareWorkflow(
            workflowId = workflowId,
            workflowNamespace = workflowNamespace,
            workflowName = workflowName,
            optionalVersion = workflowVersion,
            workflowInput = workflowInput,
            hasWaitingParent = false,
            zoneId = zoneId
        ) { message ->
            if (message.contains("not found", ignoreCase = true)) {
                throw GatewayNotFoundException(message)
            }
            throw GatewayBadRequestException(message)
        }

        // Atomically insert outbox + schedule in one transaction.
        // The outbox unique constraint on workflow_id is the idempotency guard for immediate
        // workflows. For cron-only workflows (instanceMessage is null), the schedule table's
        // own unique constraint on workflow_id provides deduplication.
        // Outbox is inserted first: if it's a duplicate, we skip the schedule insert entirely.
        val result = databaseConfig.withTransaction { conn ->
            val outboxResult = preparedWorkflow.instanceMessage?.let { msg ->
                val outboxEntry = GatewayCommandOutboxModel(
                    id = IDV7.random(),
                    instanceMessage = msg,
                    outboxScheduledFor = Clock.System.now(),
                )
                insertOrDetectConflict(
                    inserted = gatewayOutboxRepository.insert(outboxEntry, conn),
                    workflowId = workflowId,
                    workflowInfo = workflowInfo,
                    workflowInput = workflowInput,
                    loadExisting = { gatewayOutboxRepository.findByWorkflowId(workflowId) },
                )
            }

            outboxResult ?: preparedWorkflow.scheduleModel?.let {
                insertOrDetectConflict(
                    inserted = scheduleRepository.insert(it, conn),
                    workflowId = workflowId,
                    workflowInfo = workflowInfo,
                    workflowInput = workflowInput,
                    loadExisting = { scheduleRepository.findByWorkflowId(workflowId) },
                    extraConflictCheck = { existing -> existing.zone != zoneId },
                )
            }
        }

        return result ?: run {
            // Fire-and-forget lifecycle hook (outside transaction)
            preparedWorkflow.onWorkflowCreated(lifecycleHook)

            WorkflowStartResult(
                workflowId = workflowId,
                result = GatewayStartResult.ACCEPTED_NEW
            )
        }
    }

    /**
     * If the insert succeeded (row count > 0), returns null (no conflict).
     * If the insert was a no-op (unique constraint), loads the existing row and compares
     * workflowInfo + workflowInput. Returns ACCEPTED_EXISTING on match, throws on mismatch.
     */
    private suspend fun <T : com.lemline.runner.common.models.WithInstanceMessage> insertOrDetectConflict(
        inserted: Int,
        workflowId: WorkflowId,
        workflowInfo: WorkflowInfo,
        workflowInput: JsonElement,
        loadExisting: suspend () -> T?,
        extraConflictCheck: (T) -> Boolean = { false },
    ): WorkflowStartResult? {
        if (inserted > 0) return null

        val existing = loadExisting()
        if (existing?.workflowInfo != workflowInfo ||
            existing.workflowState.nodeStack.rootState.workflowInput != workflowInput ||
            extraConflictCheck(existing)
        ) {
            throw GatewayConflictException(
                "Workflow '$workflowId' already exists with different parameters: $existing"
            )
        }
        return WorkflowStartResult(
            workflowId = workflowId,
            result = GatewayStartResult.ACCEPTED_EXISTING
        )
    }

    private fun parseWorkflowId(raw: String): WorkflowId = try {
        WorkflowId(IDV7.from(raw.trim()))
    } catch (_: Exception) {
        throw GatewayBadRequestException("workflow_id must be a valid UUIDv7 string")
    }

    private fun parseWorkflowNamespace(raw: String): WorkflowNamespace =
        WorkflowNamespace(
            raw.trim().ifBlank { throw GatewayBadRequestException("Workflow namespace must be provided") })

    private fun parseWorkflowName(raw: String): WorkflowName =
        WorkflowName(raw.trim().ifBlank { throw GatewayBadRequestException("Workflow name must be provided") })

    private fun parseWorkflowVersion(raw: String): WorkflowVersion =
        WorkflowVersion(raw.trim().ifBlank { throw GatewayBadRequestException("Workflow version must be provided") })

    private fun parseZoneId(request: StartWorkflowRequest): ZoneId {
        val zoneRaw = if (request.hasZoneId()) request.zoneId.trim() else "UTC"
        val zone = zoneRaw.ifBlank { "UTC" }
        return try {
            ZoneId.of(zone)
        } catch (_: Exception) {
            throw GatewayBadRequestException("Invalid timezone ID: '$zone'")
        }
    }

    private fun parseInputJson(request: StartWorkflowRequest): JsonElement {
        val rawInput = if (request.hasInputJson()) request.inputJson else "{}"
        return try {
            LemlineJson.json.parseToJsonElement(rawInput)
        } catch (e: Exception) {
            throw GatewayBadRequestException("Invalid JSON input: ${e.message}")
        }
    }

}
