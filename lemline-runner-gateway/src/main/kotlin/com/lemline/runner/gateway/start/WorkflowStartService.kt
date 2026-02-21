// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.start

import com.lemline.common.json.LemlineJson
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.gateway.v1.StartWorkflowRequest
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.security.MessageDigest
import java.time.ZoneId
import kotlin.text.Charsets.UTF_8
import kotlinx.serialization.json.JsonElement

enum class GatewayStartResult {
    ACCEPTED_NEW,
    ACCEPTED_EXISTING,
}

data class WorkflowStartResult(
    val workflowId: WorkflowId,
    val version: WorkflowVersion,
    val result: GatewayStartResult,
)

@ApplicationScoped
class WorkflowStartService {

    @Inject
    lateinit var starter: Starter

    @Inject
    lateinit var commandEmitter: CommandEmitter

    @Inject
    lateinit var scheduleRepository: ScheduleRepository

    @Inject
    lateinit var lifecycleHook: LifecycleEventHook

    @Inject
    lateinit var reservationRepository: GatewayWorkflowReservationRepository

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    suspend fun start(request: StartWorkflowRequest, principal: GatewayPrincipal): WorkflowStartResult {
        val workflowId = parseWorkflowId(request.workflowId)
        val namespace = request.namespace.trim().ifBlank {
            throw GatewayBadRequestException("Workflow namespace must be provided")
        }
        val name = request.name.trim().ifBlank {
            throw GatewayBadRequestException("Workflow name must be provided")
        }
        val version = request.version.trim().ifBlank {
            throw GatewayBadRequestException("Workflow version must be provided")
        }

        authorizer.requireScope(principal, "lemline.start")
        authorizer.requireNamespace(principal, namespace)

        val workflowNamespace = WorkflowNamespace(namespace)
        val workflowName = WorkflowName(name)
        val workflowVersion = WorkflowVersion(version)
        val normalizedInputJson = normalizeInputJson(request)
        val workflowInput = parseInputJson(normalizedInputJson)
        val zoneId = parseZoneId(request)
        val requestFingerprint = buildRequestFingerprint(
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            inputJson = normalizedInputJson,
            zoneId = zoneId.id
        )

        val reserveResult = reservationRepository.reserve(
            workflowId = workflowId,
            workflowNamespace = namespace,
            workflowName = name,
            workflowVersion = version,
            inputJson = normalizedInputJson,
            zoneId = zoneId.id,
            requestFingerprint = requestFingerprint
        )

        when (reserveResult.type) {
            ReserveResultType.EXISTING_MISMATCH -> {
                throw GatewayConflictException(
                    "Workflow '$workflowId' already exists with a different start request fingerprint"
                )
            }

            ReserveResultType.EXISTING_SAME -> {
                return WorkflowStartResult(
                    workflowId = reserveResult.reservation.workflowId,
                    version = WorkflowVersion(reserveResult.reservation.workflowVersion),
                    result = GatewayStartResult.ACCEPTED_EXISTING
                )
            }

            ReserveResultType.NEW -> {
                try {
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

                    preparedWorkflow.scheduleModel?.let { scheduleRepository.insert(it) }
                    preparedWorkflow.instanceMessage?.let { commandEmitter.send(it) }
                    preparedWorkflow.onWorkflowCreated(lifecycleHook)

                    val effectiveVersion =
                        preparedWorkflow.instanceMessage?.workflowInfo?.version
                            ?: preparedWorkflow.scheduleModel?.workflowVersion
                            ?: workflowVersion

                    return WorkflowStartResult(
                        workflowId = workflowId,
                        version = effectiveVersion,
                        result = GatewayStartResult.ACCEPTED_NEW
                    )
                } catch (t: Throwable) {
                    runCatching { reservationRepository.deleteByWorkflowId(workflowId) }
                    throw t
                }
            }
        }
    }

    private fun parseWorkflowId(rawWorkflowId: String): WorkflowId = try {
        WorkflowId(IDV7.from(rawWorkflowId.trim()))
    } catch (e: Exception) {
        throw GatewayBadRequestException("workflow_id must be a valid UUIDv7 string")
    }

    private fun parseZoneId(request: StartWorkflowRequest): ZoneId {
        val zoneRaw = if (request.hasZoneId()) request.zoneId.trim() else "UTC"
        val zone = if (zoneRaw.isBlank()) "UTC" else zoneRaw
        return try {
            ZoneId.of(zone)
        } catch (_: Exception) {
            throw GatewayBadRequestException("Invalid timezone ID: '$zone'")
        }
    }

    private fun normalizeInputJson(request: StartWorkflowRequest): String {
        val rawInput = if (request.hasInputJson()) request.inputJson else "{}"
        return try {
            LemlineJson.jacksonMapper.readTree(rawInput).toString()
        } catch (e: Exception) {
            throw GatewayBadRequestException("Invalid JSON input: ${e.message}")
        }
    }

    private fun parseInputJson(normalizedInput: String): JsonElement = try {
        LemlineJson.json.parseToJsonElement(normalizedInput)
    } catch (e: Exception) {
        throw GatewayBadRequestException("Invalid normalized JSON input: ${e.message}")
    }

    private fun buildRequestFingerprint(
        workflowNamespace: String,
        workflowName: String,
        workflowVersion: String,
        inputJson: String,
        zoneId: String,
    ): String {
        val payload = listOf(
            workflowNamespace,
            workflowName,
            workflowVersion,
            inputJson,
            zoneId
        ).joinToString("\n")

        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
