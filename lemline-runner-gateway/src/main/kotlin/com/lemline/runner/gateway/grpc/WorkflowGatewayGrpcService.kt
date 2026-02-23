// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.gateway.v1.DefinitionStats
import com.lemline.gateway.v1.DefinitionStatsUpdate
import com.lemline.gateway.v1.GetDefinitionRequest
import com.lemline.gateway.v1.GetDefinitionResponse
import com.lemline.gateway.v1.GetDefinitionStatsRequest
import com.lemline.gateway.v1.GetDefinitionStatsResponse
import com.lemline.gateway.v1.GetInstanceTimelineRequest
import com.lemline.gateway.v1.GetInstanceTimelineResponse
import com.lemline.gateway.v1.InstanceUpdate
import com.lemline.gateway.v1.ListDefinitionsRequest
import com.lemline.gateway.v1.ListDefinitionsResponse
import com.lemline.gateway.v1.ListInstancesRequest
import com.lemline.gateway.v1.ListInstancesResponse
import com.lemline.gateway.v1.ListNamespacesRequest
import com.lemline.gateway.v1.ListNamespacesResponse
import com.lemline.gateway.v1.StartWorkflowRequest
import com.lemline.gateway.v1.StartWorkflowResponse
import com.lemline.gateway.v1.TimelineEvent
import com.lemline.gateway.v1.WatchDefinitionStatsRequest
import com.lemline.gateway.v1.WatchInstancesRequest
import com.lemline.gateway.v1.WatchWorkflowRequest
import com.lemline.gateway.v1.WorkflowAnalyticsEvent
import com.lemline.gateway.v1.WorkflowDefinitionSummary
import com.lemline.gateway.v1.WorkflowGatewayGrpc
import com.lemline.gateway.v1.WorkflowInstance
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayAuthenticationEnabled
import com.lemline.runner.gateway.auth.GatewayAuthContext
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.dashboard.DefinitionQueryService
import com.lemline.runner.gateway.dashboard.InstanceQueryFilter
import com.lemline.runner.gateway.dashboard.InstanceQueryService
import com.lemline.runner.gateway.dashboard.InstanceStreamService
import com.lemline.runner.gateway.dashboard.InstanceUpdateType
import com.lemline.runner.gateway.dashboard.StatsQueryFilter
import com.lemline.runner.gateway.dashboard.TimelineQueryService
import com.lemline.runner.gateway.dashboard.WatchInstancesFilter
import com.lemline.runner.gateway.dashboard.WorkflowRuntimeStatus
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.start.GatewayStartResult
import com.lemline.runner.gateway.start.WorkflowStartService
import com.lemline.runner.gateway.watch.WorkflowWatchService
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import io.quarkus.grpc.GrpcService
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.time.Instant
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@GrpcService
@Singleton
class WorkflowGatewayGrpcService(
    private val config: LemlineConfiguration,
) : WorkflowGatewayGrpc.WorkflowGatewayImplBase() {

    @Inject
    lateinit var startService: WorkflowStartService

    @Inject
    lateinit var watchService: WorkflowWatchService

    @Inject
    lateinit var definitionQueryService: DefinitionQueryService

    @Inject
    lateinit var statsQueryService: com.lemline.runner.gateway.dashboard.StatsQueryService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    @Inject
    lateinit var timelineQueryService: TimelineQueryService

    @Inject
    lateinit var instanceStreamService: InstanceStreamService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startWorkflow(request: StartWorkflowRequest, responseObserver: StreamObserver<StartWorkflowResponse>) {
        val principal = requirePrincipal(responseObserver) ?: return

        val callObserver = responseObserver as? ServerCallStreamObserver<StartWorkflowResponse>
        scope.launch {
            try {
                val startResult = startService.start(request, principal)
                if (callObserver?.isCancelled == true) return@launch

                val response = StartWorkflowResponse.newBuilder()
                    .setWorkflowId(startResult.workflowId.toString())
                    .setResult(
                        when (startResult.result) {
                            GatewayStartResult.ACCEPTED_NEW -> StartWorkflowResponse.Result.START_WORKFLOW_RESULT_ACCEPTED_NEW
                            GatewayStartResult.ACCEPTED_EXISTING -> StartWorkflowResponse.Result.START_WORKFLOW_RESULT_ACCEPTED_EXISTING
                        }
                    )
                    .build()

                responseObserver.onNext(response)
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchWorkflow(
        request: WatchWorkflowRequest,
        responseObserver: StreamObserver<WorkflowAnalyticsEvent>
    ) {
        val principal = requirePrincipal(responseObserver) ?: return

        val callObserver = responseObserver as? ServerCallStreamObserver<WorkflowAnalyticsEvent>
        scope.launch {
            try {
                val workflowId = parseWorkflowId(request)

                watchService.watch(workflowId, principal) { event ->
                    if (callObserver?.isCancelled == true) return@watch false

                    responseObserver.onNext(
                        WorkflowAnalyticsEvent.newBuilder()
                            .setCursor(event.cursor)
                            .setCloudeventJson(event.cloudEventJson)
                            .build()
                    )
                    true
                }

                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun listNamespaces(
        request: ListNamespacesRequest,
        responseObserver: StreamObserver<ListNamespacesResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListNamespacesResponse>

        scope.launch {
            try {
                val namespaces = definitionQueryService.listNamespaces()
                if (callObserver?.isCancelled == true) return@launch

                responseObserver.onNext(
                    ListNamespacesResponse.newBuilder()
                        .addAllNamespaces(namespaces)
                        .build()
                )
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun listDefinitions(
        request: ListDefinitionsRequest,
        responseObserver: StreamObserver<ListDefinitionsResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListDefinitionsResponse>

        scope.launch {
            try {
                val namespace = request.namespace.trim()
                if (namespace.isBlank()) {
                    throw GatewayBadRequestException("namespace must be provided")
                }

                val name = request.takeIf { it.hasName() }?.name?.trim()?.ifBlank { null }
                val latestOnly = if (request.hasLatestOnly()) request.latestOnly else true

                val definitions = definitionQueryService.listDefinitions(namespace, name, latestOnly)
                if (callObserver?.isCancelled == true) return@launch

                val response = ListDefinitionsResponse.newBuilder()
                    .addAllDefinitions(
                        definitions.map { row ->
                            WorkflowDefinitionSummary.newBuilder()
                                .setNamespace(row.namespace)
                                .setName(row.name)
                                .setVersion(row.version)
                                .setIsLatest(row.isLatest)
                                .setVersionCount(row.versionCount)
                                .setCreatedAt(row.createdAt.toString())
                                .setUpdatedAt(row.updatedAt?.toString() ?: "")
                                .build()
                        }
                    )
                    .build()

                responseObserver.onNext(response)
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun getDefinition(
        request: GetDefinitionRequest,
        responseObserver: StreamObserver<GetDefinitionResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<GetDefinitionResponse>

        scope.launch {
            try {
                val namespace = request.namespace.trim()
                val name = request.name.trim()
                if (namespace.isBlank()) throw GatewayBadRequestException("namespace must be provided")
                if (name.isBlank()) throw GatewayBadRequestException("name must be provided")

                val version = if (request.hasVersion()) request.version.trim().ifBlank { null } else null
                val definition = definitionQueryService.getDefinition(namespace, name, version)
                    ?: throw GatewayNotFoundException("Workflow definition '$namespace/$name${version?.let { ":$it" } ?: ""}' not found")

                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onNext(
                    GetDefinitionResponse.newBuilder()
                        .setNamespace(definition.namespace)
                        .setName(definition.name)
                        .setVersion(definition.version)
                        .setDefinition(definition.definition)
                        .addAllAvailableVersions(definition.availableVersions)
                        .build()
                )
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun getDefinitionStats(
        request: GetDefinitionStatsRequest,
        responseObserver: StreamObserver<GetDefinitionStatsResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<GetDefinitionStatsResponse>

        scope.launch {
            try {
                val filter = StatsQueryFilter(
                    namespace = optionalString(request.hasNamespace(), request.namespace),
                    name = optionalString(request.hasName(), request.name),
                    version = optionalString(request.hasVersion(), request.version),
                    timeFrom = optionalInstant(request.hasTimeFrom(), request.timeFrom, "time_from"),
                    timeTo = optionalInstant(request.hasTimeTo(), request.timeTo, "time_to"),
                )

                val stats = statsQueryService.queryStats(filter)
                if (callObserver?.isCancelled == true) return@launch

                val response = GetDefinitionStatsResponse.newBuilder()
                    .addAllStats(stats.map(::toProtoStats))
                    .build()

                responseObserver.onNext(response)
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun listInstances(
        request: ListInstancesRequest,
        responseObserver: StreamObserver<ListInstancesResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListInstancesResponse>

        scope.launch {
            try {
                val filter = InstanceQueryFilter(
                    namespace = optionalString(request.hasNamespace(), request.namespace),
                    name = optionalString(request.hasName(), request.name),
                    version = optionalString(request.hasVersion(), request.version),
                    status = WorkflowRuntimeStatus.fromFilter(optionalString(request.hasStatus(), request.status)),
                    timeFrom = optionalInstant(request.hasTimeFrom(), request.timeFrom, "time_from"),
                    timeTo = optionalInstant(request.hasTimeTo(), request.timeTo, "time_to"),
                    workflowId = optionalUuid(request.hasWorkflowId(), request.workflowId, "workflow_id"),
                    workflowIdPrefix = optionalString(request.hasWorkflowIdPrefix(), request.workflowIdPrefix),
                    pageSize = request.pageSize,
                    pageCursor = optionalString(request.hasPageCursor(), request.pageCursor),
                )

                val result = instanceQueryService.listInstances(filter)
                if (callObserver?.isCancelled == true) return@launch

                val responseBuilder = ListInstancesResponse.newBuilder()
                    .setTotalCount(result.totalCount)
                    .addAllInstances(result.instances.map(::toProtoInstance))

                result.nextCursor?.let { responseBuilder.nextCursor = it }

                responseObserver.onNext(responseBuilder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun getInstanceTimeline(
        request: GetInstanceTimelineRequest,
        responseObserver: StreamObserver<GetInstanceTimelineResponse>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<GetInstanceTimelineResponse>

        scope.launch {
            try {
                val workflowId = parseWorkflowId(request.workflowId, "workflow_id")
                val timeline = timelineQueryService.getInstanceTimeline(workflowId, request.includeDefinition)
                    ?: throw GatewayNotFoundException("Workflow '$workflowId' not found")

                if (callObserver?.isCancelled == true) return@launch

                val responseBuilder = GetInstanceTimelineResponse.newBuilder()
                    .setWorkflowId(timeline.workflowId.toString())
                    .setNamespace(timeline.namespace)
                    .setName(timeline.name)
                    .setVersion(timeline.version)
                    .setStatus(timeline.status.name)
                    .addAllEvents(
                        timeline.events.map { event ->
                            val builder = TimelineEvent.newBuilder()
                                .setSequence(event.sequence)
                                .setEventType(event.eventType)
                                .setEventTime(event.eventTime?.toString() ?: "")
                                .setCategory(event.category)
                                .setPayloadJson(event.payloadJson)

                            event.taskPointer?.let { builder.taskPointer = it }
                            event.taskName?.let { builder.taskName = it }
                            builder.build()
                        }
                    )

                timeline.definition?.let { responseBuilder.definition = it }

                responseObserver.onNext(responseBuilder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchDefinitionStats(
        request: WatchDefinitionStatsRequest,
        responseObserver: StreamObserver<DefinitionStatsUpdate>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<DefinitionStatsUpdate>

        scope.launch {
            try {
                val filter = StatsQueryFilter(
                    namespace = optionalString(request.hasNamespace(), request.namespace),
                    name = optionalString(request.hasName(), request.name),
                    version = optionalString(request.hasVersion(), request.version),
                    timeFrom = optionalInstant(request.hasTimeFrom(), request.timeFrom, "time_from"),
                    timeTo = optionalInstant(request.hasTimeTo(), request.timeTo, "time_to"),
                )

                instanceStreamService.watchDefinitionStats(filter) { stats ->
                    if (callObserver?.isCancelled == true) return@watchDefinitionStats false
                    responseObserver.onNext(
                        DefinitionStatsUpdate.newBuilder()
                            .setStats(toProtoStats(stats))
                            .build()
                    )
                    true
                }

                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchInstances(
        request: WatchInstancesRequest,
        responseObserver: StreamObserver<InstanceUpdate>
    ) {
        requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<InstanceUpdate>

        scope.launch {
            try {
                val filter = WatchInstancesFilter(
                    namespace = optionalString(request.hasNamespace(), request.namespace),
                    name = optionalString(request.hasName(), request.name),
                    version = optionalString(request.hasVersion(), request.version),
                )
                val afterSequence = if (request.hasAfterSequence()) request.afterSequence else null

                instanceStreamService.watchInstances(filter, afterSequence) { update ->
                    if (callObserver?.isCancelled == true) return@watchInstances false
                    responseObserver.onNext(
                        InstanceUpdate.newBuilder()
                            .setInstance(toProtoInstance(update.instance))
                            .setUpdateType(
                                when (update.updateType) {
                                    InstanceUpdateType.CREATED -> "CREATED"
                                    InstanceUpdateType.UPDATED -> "UPDATED"
                                }
                            )
                            .setSequence(update.sequence)
                            .build()
                    )
                    true
                }

                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    private fun toProtoStats(row: com.lemline.runner.gateway.dashboard.DefinitionStatsRow): DefinitionStats {
        val builder = DefinitionStats.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setStarted(row.started)
            .setCompleted(row.completed)
            .setFaulted(row.faulted)
            .setRunning(row.running)

        row.version?.let { builder.version = it }
        return builder.build()
    }

    private fun toProtoInstance(row: com.lemline.runner.gateway.dashboard.WorkflowInstanceRow): WorkflowInstance {
        val builder = WorkflowInstance.newBuilder()
            .setWorkflowId(row.workflowId.toString())
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setStatus(row.status.name)
            .setCreatedAt(row.createdAt?.toString() ?: "")

        row.startedAt?.let { builder.startedAt = it.toString() }
        row.completedAt?.let { builder.completedAt = it.toString() }
        row.faultedAt?.let { builder.faultedAt = it.toString() }
        row.durationMs?.let { builder.durationMs = it }
        row.error?.let {
            builder.error = com.lemline.gateway.v1.ErrorSummary.newBuilder()
                .setType(it.type)
                .setStatus(it.status)
                .setTitle(it.title)
                .build()
        }
        return builder.build()
    }

    private fun requirePrincipal(responseObserver: StreamObserver<*>): GatewayPrincipal? {
        return resolvePrincipalOrNull() ?: run {
            responseObserver.onError(
                Status.UNAUTHENTICATED.withDescription("Missing authenticated principal").asRuntimeException()
            )
            null
        }
    }

    private fun resolvePrincipalOrNull(): GatewayPrincipal? {
        return GatewayAuthContext.getOrNull()
            ?: if (!config.gatewayAuthenticationEnabled) GatewayPrincipal.dashboardAnonymous else null
    }

    private fun parseWorkflowId(request: WatchWorkflowRequest): WorkflowId {
        if (request.workflowId.isBlank()) {
            throw GatewayBadRequestException("workflow_id must be provided")
        }

        return try {
            WorkflowId(IDV7.from(request.workflowId.trim()))
        } catch (_: Exception) {
            throw GatewayBadRequestException("workflow_id must be a valid UUIDv7 string")
        }
    }

    private fun parseWorkflowId(raw: String, field: String): UUID {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw GatewayBadRequestException("$field must be provided")
        }

        return parseUuid(trimmed, field)
    }

    private fun optionalUuid(hasField: Boolean, raw: String, field: String): UUID? {
        if (!hasField) return null
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return parseUuid(trimmed, field)
    }

    private fun parseUuid(raw: String, field: String): UUID = try {
        UUID.fromString(raw)
    } catch (_: Exception) {
        throw GatewayBadRequestException("$field must be a valid UUID string")
    }

    private fun optionalString(hasField: Boolean, raw: String): String? =
        if (!hasField) {
            null
        } else {
            raw.trim().ifBlank { null }
        }

    private fun optionalInstant(hasField: Boolean, raw: String, field: String): Instant? {
        if (!hasField) return null
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        return try {
            Instant.parse(trimmed)
        } catch (_: Exception) {
            throw GatewayBadRequestException("$field must be an ISO-8601 timestamp")
        }
    }

    private fun toStatusException(t: Throwable): StatusRuntimeException {
        if (t is StatusRuntimeException) return t

        val (status, message) = when (t) {
            is GatewayBadRequestException -> Status.INVALID_ARGUMENT to t.message
            is GatewayNotFoundException -> Status.NOT_FOUND to t.message
            is GatewayConflictException -> Status.ALREADY_EXISTS to t.message
            is GatewayPermissionDeniedException -> Status.PERMISSION_DENIED to t.message
            is IllegalArgumentException -> Status.INVALID_ARGUMENT to t.message
            else -> Status.INTERNAL to (t.message ?: "Unhandled gateway error")
        }

        return status.withDescription(message).withCause(t).asRuntimeException()
    }

    @Suppress("unused")
    fun onShutdown(@Observes event: ShutdownEvent) {
        scope.cancel("Gateway shutting down")
    }
}
