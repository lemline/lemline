// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.google.protobuf.Value
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.outbox.GatewayCommandModel
import com.lemline.runner.gateway.outbox.GatewayCommandOutbox
import com.lemline.runner.gateway.outbox.GatewayCommandRepository
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class StartInstanceOutcome {
    ACCEPTED_NEW,
    ACCEPTED_EXISTING,
}

data class InstanceStartResult(
    val instanceId: WorkflowId,
    val namespace: WorkflowNamespace,
    val name: WorkflowName,
    val version: WorkflowVersion,
    val result: StartInstanceOutcome,
    val scheduleOnly: Boolean,
    val nextRunAt: Instant?,
)

@ApplicationScoped
class InstanceStartService {

    @Inject
    lateinit var starter: Starter

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var gatewayCommandRepository: GatewayCommandRepository

    @Inject
    lateinit var gatewayCommandOutbox: GatewayCommandOutbox

    @Inject
    lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var namespaceActivityMonitor: NamespaceActivityMonitor

    suspend fun start(request: StartInstanceRequest, principal: GatewayPrincipal): InstanceStartResult {
        val instanceId = parseInstanceId(request)
        val workflowNamespace = parseWorkflowNamespace(request.namespace)
        val workflowName = parseWorkflowName(request.name)
        val optionalVersion = parseWorkflowVersion(request)
        val zoneId = parseZoneId(request)

        authorizer.requireScope(principal, "lemline.start")
        authorizer.requireNamespace(principal, workflowNamespace.toString())

        val workflowInput = parseInput(request)
        val preparedWorkflow = starter.prepareWorkflow(
            workflowId = instanceId,
            workflowNamespace = workflowNamespace,
            workflowName = workflowName,
            optionalVersion = optionalVersion,
            workflowInput = workflowInput,
            hasWaitingParent = false,
            zoneId = zoneId,
        ) { message ->
            if (message.contains("not found", ignoreCase = true)) {
                throw GatewayNotFoundException(message)
            }
            throw GatewayBadRequestException(message)
        }

        val resolvedWorkflowInfo = preparedWorkflow.instanceMessage?.workflowInfo
            ?: preparedWorkflow.scheduleModel?.instanceMessage?.workflowInfo
            ?: error("Prepared workflow must contain workflow info")

        var newCommandId: IDV7? = null
        var nextRunAt = preparedWorkflow.scheduleModel?.outboxScheduledFor
        val existingResult = databaseConfig.withTransaction { conn ->
            val outboxResult = preparedWorkflow.instanceMessage?.let { message ->
                val outboxEntry = GatewayCommandModel(
                    id = IDV7.random(),
                    instanceMessage = message,
                    outboxScheduledFor = Clock.System.now(),
                )
                insertOrDetectConflict(
                    inserted = gatewayCommandRepository.insert(outboxEntry, conn),
                    instanceId = instanceId,
                    workflowInfo = resolvedWorkflowInfo,
                    workflowInput = workflowInput,
                    loadExisting = { gatewayCommandRepository.findByWorkflowId(instanceId) },
                ).also { if (it == null) newCommandId = outboxEntry.id }
            }

            outboxResult ?: preparedWorkflow.scheduleModel?.let { scheduleModel ->
                insertOrDetectConflict(
                    inserted = scheduleRepository.insert(scheduleModel, conn),
                    instanceId = instanceId,
                    workflowInfo = resolvedWorkflowInfo,
                    workflowInput = workflowInput,
                    loadExisting = { scheduleRepository.findByWorkflowId(instanceId) },
                    extraConflictCheck = { existing -> existing.zone != zoneId },
                    onExisting = { existing -> nextRunAt = existing.outboxScheduledFor },
                )
            }
        }

        val outcome = existingResult ?: run {
            preparedWorkflow.onWorkflowCreated(lifecycleHook)
            newCommandId?.let { gatewayCommandOutbox.processNow(it) }
            // Loud first-contact signal: WARN + counter when the namespace shows no runner activity
            namespaceActivityMonitor.onInstanceAccepted(resolvedWorkflowInfo.namespace.toString())
            StartInstanceOutcome.ACCEPTED_NEW
        }

        return InstanceStartResult(
            instanceId = instanceId,
            namespace = resolvedWorkflowInfo.namespace,
            name = resolvedWorkflowInfo.name,
            version = resolvedWorkflowInfo.version,
            result = outcome,
            scheduleOnly = preparedWorkflow.instanceMessage == null,
            nextRunAt = if (preparedWorkflow.instanceMessage == null) nextRunAt else null,
        )
    }

    private suspend fun <T : com.lemline.runner.common.models.WithInstanceMessage> insertOrDetectConflict(
        inserted: Int,
        instanceId: WorkflowId,
        workflowInfo: WorkflowInfo,
        workflowInput: JsonElement,
        loadExisting: suspend () -> T?,
        extraConflictCheck: (T) -> Boolean = { false },
        onExisting: (T) -> Unit = {},
    ): StartInstanceOutcome? {
        if (inserted > 0) return null

        val existing = loadExisting()
        if (existing?.workflowInfo != workflowInfo ||
            existing.workflowState.nodeStack.rootState.workflowInput != workflowInput ||
            extraConflictCheck(existing)
        ) {
            throw GatewayConflictException(
                "Instance '$instanceId' already exists with different parameters: $existing"
            )
        }

        onExisting(existing)
        return StartInstanceOutcome.ACCEPTED_EXISTING
    }

    private fun parseInstanceId(request: StartInstanceRequest): WorkflowId {
        if (!request.hasInstanceId()) return WorkflowId(IDV7.random())

        val raw = request.instanceId.trim()
        if (raw.isBlank()) {
            throw GatewayBadRequestException("instance_id must not be blank")
        }

        return try {
            WorkflowId(IDV7.from(raw))
        } catch (_: Exception) {
            throw GatewayBadRequestException("instance_id must be a valid UUIDv7 string")
        }
    }

    private fun parseWorkflowNamespace(raw: String): WorkflowNamespace =
        WorkflowNamespace(raw.trim().ifBlank { throw GatewayBadRequestException("namespace must be provided") })

    private fun parseWorkflowName(raw: String): WorkflowName =
        WorkflowName(raw.trim().ifBlank { throw GatewayBadRequestException("name must be provided") })

    private fun parseWorkflowVersion(request: StartInstanceRequest): WorkflowVersion? {
        if (!request.hasVersion()) return null
        val raw = request.version.trim()
        return raw.takeIf { it.isNotBlank() }?.let(::WorkflowVersion)
    }

    private fun parseZoneId(request: StartInstanceRequest): ZoneId {
        val zoneRaw = if (request.hasZoneId()) request.zoneId.trim() else "UTC"
        val zone = zoneRaw.ifBlank { "UTC" }
        return try {
            ZoneId.of(zone)
        } catch (_: Exception) {
            throw GatewayBadRequestException("Invalid timezone ID: '$zone'")
        }
    }

    private fun parseInput(request: StartInstanceRequest): JsonElement {
        if (!request.hasInput()) return JsonObject(emptyMap())
        return valueToJson(request.input)
    }

    private fun valueToJson(value: Value): JsonElement =
        when (value.kindCase) {
            Value.KindCase.NULL_VALUE -> JsonNull
            Value.KindCase.NUMBER_VALUE -> JsonPrimitive(value.numberValue)
            Value.KindCase.STRING_VALUE -> JsonPrimitive(value.stringValue)
            Value.KindCase.BOOL_VALUE -> JsonPrimitive(value.boolValue)
            Value.KindCase.STRUCT_VALUE -> JsonObject(
                value.structValue.fieldsMap.mapValues { (_, child) -> valueToJson(child) }
            )

            Value.KindCase.LIST_VALUE -> JsonArray(
                value.listValue.valuesList.map(::valueToJson)
            )

            Value.KindCase.KIND_NOT_SET -> throw GatewayBadRequestException("input must contain a JSON value")
        }
}
