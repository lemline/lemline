// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.google.protobuf.Duration as ProtoDuration
import com.google.protobuf.Timestamp as ProtoTimestamp
import com.lemline.common.values.IDV7
import com.lemline.gateway.v1.DefinitionStats
import com.lemline.gateway.v1.DefinitionStatsChange
import com.lemline.gateway.v1.DefinitionStatsChangeType
import com.lemline.gateway.v1.DefinitionSummary
import com.lemline.gateway.v1.GetDefinitionRequest
import com.lemline.gateway.v1.GetDefinitionResponse
import com.lemline.gateway.v1.GetInstanceRequest
import com.lemline.gateway.v1.GetInstanceResponse
import com.lemline.gateway.v1.InstanceChange
import com.lemline.gateway.v1.InstanceChangeType
import com.lemline.gateway.v1.InstanceEvent
import com.lemline.gateway.v1.InstanceStatus as ProtoInstanceStatus
import com.lemline.gateway.v1.InstanceSummary
import com.lemline.gateway.v1.ListDefinitionStatsRequest
import com.lemline.gateway.v1.ListDefinitionStatsResponse
import com.lemline.gateway.v1.ListDefinitionsRequest
import com.lemline.gateway.v1.ListDefinitionsResponse
import com.lemline.gateway.v1.ListInstanceEventsRequest
import com.lemline.gateway.v1.ListInstanceEventsResponse
import com.lemline.gateway.v1.ListInstancesRequest
import com.lemline.gateway.v1.ListInstancesResponse
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.gateway.v1.StartInstanceResponse
import com.lemline.gateway.v1.StartInstanceResult
import com.lemline.gateway.v1.WatchDefinitionStatsRequest
import com.lemline.gateway.v1.WatchDefinitionStatsResponse
import com.lemline.gateway.v1.WatchInstanceEventsRequest
import com.lemline.gateway.v1.WatchInstanceEventsResponse
import com.lemline.gateway.v1.WatchInstancesRequest
import com.lemline.gateway.v1.WatchInstancesResponse
import com.lemline.gateway.v1.WorkflowDefinition
import com.lemline.gateway.v1.WorkflowGatewayServiceGrpc
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayAuthenticationEnabled
import com.lemline.runner.gateway.auth.GatewayAuthContext
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.dashboard.ChangeType
import com.lemline.runner.gateway.dashboard.DefinitionDetailsRow
import com.lemline.runner.gateway.dashboard.DefinitionListFilter
import com.lemline.runner.gateway.dashboard.DefinitionPageBoundary
import com.lemline.runner.gateway.dashboard.DefinitionQueryService
import com.lemline.runner.gateway.dashboard.DefinitionStatsChangeRow
import com.lemline.runner.gateway.dashboard.DefinitionStatsFilter
import com.lemline.runner.gateway.dashboard.DefinitionStatsRow
import com.lemline.runner.gateway.dashboard.ErrorSummaryRow
import com.lemline.runner.gateway.dashboard.InstanceChangeRow
import com.lemline.runner.gateway.dashboard.InstanceEventFilter
import com.lemline.runner.gateway.dashboard.InstanceEventQueryService
import com.lemline.runner.gateway.dashboard.InstanceEventRow
import com.lemline.runner.gateway.dashboard.InstanceListFilter
import com.lemline.runner.gateway.dashboard.InstancePageBoundary
import com.lemline.runner.gateway.dashboard.InstanceQueryService
import com.lemline.runner.gateway.dashboard.InstanceStatus
import com.lemline.runner.gateway.dashboard.InstanceStreamService
import com.lemline.runner.gateway.dashboard.InstanceSummaryRow
import com.lemline.runner.gateway.dashboard.StatsQueryService
import com.lemline.runner.gateway.errors.GatewayBadRequestException
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.start.InstanceStartResult
import com.lemline.runner.gateway.start.InstanceStartService
import com.lemline.runner.gateway.start.StartInstanceOutcome
import com.lemline.runner.gateway.watch.InstanceEventStreamService
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
) : WorkflowGatewayServiceGrpc.WorkflowGatewayServiceImplBase() {

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var startService: InstanceStartService

    @Inject
    lateinit var instanceEventStreamService: InstanceEventStreamService

    @Inject
    lateinit var definitionQueryService: DefinitionQueryService

    @Inject
    lateinit var statsQueryService: StatsQueryService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    @Inject
    lateinit var instanceEventQueryService: InstanceEventQueryService

    @Inject
    lateinit var instanceStreamService: InstanceStreamService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun listDefinitions(
        request: ListDefinitionsRequest,
        responseObserver: StreamObserver<ListDefinitionsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListDefinitionsResponse>

        scope.launch {
            try {
                val namespace = optionalString(request.hasNamespace(), request.namespace)
                val allowedNamespaces = allowedNamespacesForRead(principal, namespace)
                val filter = DefinitionListFilter(
                    namespace = namespace,
                    name = optionalString(request.hasName(), request.name),
                    latestOnly = if (request.hasLatestOnly()) request.latestOnly else true,
                    allowedNamespaces = allowedNamespaces,
                    pageSize = request.pageSize,
                )

                val pageCursor = if (request.hasPageCursor()) {
                    GatewayCursorCodec.decodeDefinitionPageCursor(request.pageCursor).also {
                        requireDefinitionPageCursorMatches(it, filter)
                    }
                } else {
                    null
                }
                val snapshotAt = pageCursor?.snapshotAt?.let(::parseCursorInstant) ?: Instant.now()
                val result = definitionQueryService.listDefinitions(
                    filter = filter,
                    snapshotAt = snapshotAt,
                    page = pageCursor?.toBoundary(),
                )

                if (callObserver?.isCancelled == true) return@launch

                val builder = ListDefinitionsResponse.newBuilder()
                    .addAllDefinitions(result.definitions.map(::toProtoDefinitionSummary))
                    .setTotalCount(result.totalCount)

                result.nextPage?.let {
                    builder.setNextPageCursor(
                        GatewayCursorCodec.encodeDefinitionPageCursor(
                            DefinitionPageCursor(
                                namespace = filter.namespace,
                                name = filter.name,
                                latestOnly = filter.latestOnly,
                                allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                                snapshotAt = snapshotAt.toString(),
                                lastNamespace = it.namespace,
                                lastName = it.name,
                                lastVersion = it.version,
                            )
                        )
                    )
                }

                responseObserver.onNext(builder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun getDefinition(
        request: GetDefinitionRequest,
        responseObserver: StreamObserver<GetDefinitionResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<GetDefinitionResponse>

        scope.launch {
            try {
                val namespace = requiredString(request.namespace, "namespace")
                val name = requiredString(request.name, "name")
                authorizer.requireNamespace(principal, namespace)

                val definition = definitionQueryService.getDefinition(
                    namespace = namespace,
                    name = name,
                    version = optionalString(request.hasVersion(), request.version),
                ) ?: throw GatewayNotFoundException("Definition '$namespace/$name' not found")

                if (callObserver?.isCancelled == true) return@launch

                responseObserver.onNext(
                    GetDefinitionResponse.newBuilder()
                        .setDefinition(toProtoWorkflowDefinition(definition))
                        .build()
                )
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun listDefinitionStats(
        request: ListDefinitionStatsRequest,
        responseObserver: StreamObserver<ListDefinitionStatsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListDefinitionStatsResponse>

        scope.launch {
            try {
                val filter = parseDefinitionStatsFilter(request, principal)
                val pageCursor = if (request.hasPageCursor()) {
                    GatewayCursorCodec.decodeDefinitionStatsPageCursor(request.pageCursor).also {
                        requireDefinitionStatsPageCursorMatches(it, filter)
                    }
                } else {
                    null
                }
                val snapshotSequence = pageCursor?.snapshotSequence ?: instanceQueryService.latestSequence()
                val result = statsQueryService.listDefinitionStats(
                    filter = filter,
                    snapshotSequence = snapshotSequence,
                    page = pageCursor?.toBoundary(),
                )

                if (callObserver?.isCancelled == true) return@launch

                val watchCursor = GatewayCursorCodec.encodeDefinitionStatsWatchCursor(
                    DefinitionStatsWatchCursor(
                        namespace = filter.namespace,
                        name = filter.name,
                        version = filter.version,
                        allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                        timeFrom = filter.timeFrom.toString(),
                        timeTo = filter.timeTo.toString(),
                        sequence = result.watchSequence,
                    )
                )

                val builder = ListDefinitionStatsResponse.newBuilder()
                    .addAllStats(result.stats.map(::toProtoDefinitionStats))
                    .setTotalCount(result.totalCount)
                    .setWatchCursor(watchCursor)

                result.nextPage?.let {
                    builder.setNextPageCursor(
                        GatewayCursorCodec.encodeDefinitionStatsPageCursor(
                            DefinitionStatsPageCursor(
                                namespace = filter.namespace,
                                name = filter.name,
                                version = filter.version,
                                allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                                timeFrom = filter.timeFrom.toString(),
                                timeTo = filter.timeTo.toString(),
                                snapshotSequence = result.watchSequence,
                                lastNamespace = it.namespace,
                                lastName = it.name,
                                lastVersion = it.version,
                            )
                        )
                    )
                }

                responseObserver.onNext(builder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchDefinitionStats(
        request: WatchDefinitionStatsRequest,
        responseObserver: StreamObserver<WatchDefinitionStatsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<WatchDefinitionStatsResponse>

        scope.launch {
            try {
                authorizer.requireScope(principal, "lemline.watch")
                val filter = parseDefinitionStatsFilter(request, principal)
                val afterSequence = if (request.hasAfterCursor()) {
                    GatewayCursorCodec.decodeDefinitionStatsWatchCursor(request.afterCursor).also {
                        requireDefinitionStatsWatchCursorMatches(it, filter)
                    }.sequence
                } else {
                    null
                }

                instanceStreamService.watchDefinitionStats(filter, afterSequence) { change ->
                    if (callObserver?.isCancelled == true) return@watchDefinitionStats false

                    val cursor = GatewayCursorCodec.encodeDefinitionStatsWatchCursor(
                        DefinitionStatsWatchCursor(
                            namespace = filter.namespace,
                            name = filter.name,
                            version = filter.version,
                            allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                            timeFrom = filter.timeFrom.toString(),
                            timeTo = filter.timeTo.toString(),
                            sequence = change.cursor,
                        )
                    )

                    responseObserver.onNext(
                        WatchDefinitionStatsResponse.newBuilder()
                            .setCursor(cursor)
                            .setChange(toProtoDefinitionStatsChange(change))
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

    override fun startInstance(
        request: StartInstanceRequest,
        responseObserver: StreamObserver<StartInstanceResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<StartInstanceResponse>

        scope.launch {
            try {
                val result = startService.start(request, principal)
                if (callObserver?.isCancelled == true) return@launch

                responseObserver.onNext(
                    StartInstanceResponse.newBuilder()
                        .setInstanceId(result.instanceId.toString())
                        .setNamespace(result.namespace.toString())
                        .setName(result.name.toString())
                        .setVersion(result.version.toString())
                        .setResult(toProtoStartInstanceResult(result))
                        .build()
                )
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun getInstance(
        request: GetInstanceRequest,
        responseObserver: StreamObserver<GetInstanceResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<GetInstanceResponse>

        scope.launch {
            try {
                val instanceId = parseInstanceUuid(request.instanceId, "instance_id")
                val snapshotSequence = instanceQueryService.latestSequence()
                val row = instanceQueryService.findInstance(instanceId, snapshotSequence)
                    ?: throw GatewayNotFoundException("Instance '$instanceId' not found")
                authorizer.requireNamespace(principal, row.instance.namespace)

                if (callObserver?.isCancelled == true) return@launch

                val builder = GetInstanceResponse.newBuilder()
                    .setInstance(toProtoInstanceSummary(row.instance))

                row.latestEventSequence?.let {
                    builder.setLatestEventCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(
                                instanceId = instanceId.toString(),
                                sequence = it,
                            )
                        )
                    )
                }

                responseObserver.onNext(builder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun listInstances(
        request: ListInstancesRequest,
        responseObserver: StreamObserver<ListInstancesResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListInstancesResponse>

        scope.launch {
            try {
                val filter = parseInstanceListFilter(request, principal)
                val pageCursor = if (request.hasPageCursor()) {
                    GatewayCursorCodec.decodeInstancePageCursor(request.pageCursor).also {
                        requireInstancePageCursorMatches(it, filter)
                    }
                } else {
                    null
                }
                val snapshotSequence = pageCursor?.snapshotSequence ?: instanceQueryService.latestSequence()
                val result = instanceQueryService.listInstances(
                    filter = filter,
                    snapshotSequence = snapshotSequence,
                    page = pageCursor?.toBoundary(),
                )

                if (callObserver?.isCancelled == true) return@launch

                val watchCursor = GatewayCursorCodec.encodeInstanceWatchCursor(
                    InstanceWatchCursor(
                        namespace = filter.namespace,
                        name = filter.name,
                        version = filter.version,
                        status = filter.status?.name,
                        createdFrom = filter.createdFrom?.toString(),
                        createdTo = filter.createdTo?.toString(),
                        instanceIdPrefix = filter.instanceIdPrefix,
                        allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                        sequence = result.watchSequence,
                    )
                )

                val builder = ListInstancesResponse.newBuilder()
                    .addAllInstances(result.instances.map(::toProtoInstanceSummary))
                    .setTotalCount(result.totalCount)
                    .setWatchCursor(watchCursor)

                result.nextPage?.let {
                    builder.setNextPageCursor(
                        GatewayCursorCodec.encodeInstancePageCursor(
                            InstancePageCursor(
                                namespace = filter.namespace,
                                name = filter.name,
                                version = filter.version,
                                status = filter.status?.name,
                                createdFrom = filter.createdFrom?.toString(),
                                createdTo = filter.createdTo?.toString(),
                                instanceIdPrefix = filter.instanceIdPrefix,
                                allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                                snapshotSequence = result.watchSequence,
                                lastCreatedAt = it.createdAt.toString(),
                                lastInstanceId = it.instanceId.toString(),
                            )
                        )
                    )
                }

                responseObserver.onNext(builder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchInstances(
        request: WatchInstancesRequest,
        responseObserver: StreamObserver<WatchInstancesResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<WatchInstancesResponse>

        scope.launch {
            try {
                authorizer.requireScope(principal, "lemline.watch")
                val filter = parseInstanceListFilter(request, principal)
                val afterSequence = if (request.hasAfterCursor()) {
                    GatewayCursorCodec.decodeInstanceWatchCursor(request.afterCursor).also {
                        requireInstanceWatchCursorMatches(it, filter)
                    }.sequence
                } else {
                    null
                }

                instanceStreamService.watchInstances(filter, afterSequence) { change ->
                    if (callObserver?.isCancelled == true) return@watchInstances false

                    val cursor = GatewayCursorCodec.encodeInstanceWatchCursor(
                        InstanceWatchCursor(
                            namespace = filter.namespace,
                            name = filter.name,
                            version = filter.version,
                            status = filter.status?.name,
                            createdFrom = filter.createdFrom?.toString(),
                            createdTo = filter.createdTo?.toString(),
                            instanceIdPrefix = filter.instanceIdPrefix,
                            allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                            sequence = change.cursor,
                        )
                    )

                    responseObserver.onNext(
                        WatchInstancesResponse.newBuilder()
                            .setCursor(cursor)
                            .setChange(toProtoInstanceChange(change))
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

    override fun listInstanceEvents(
        request: ListInstanceEventsRequest,
        responseObserver: StreamObserver<ListInstanceEventsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<ListInstanceEventsResponse>

        scope.launch {
            try {
                val instanceId = parseInstanceUuid(request.instanceId, "instance_id")
                authorizeInstanceRead(principal, instanceId)

                val afterSequence = if (request.hasAfterCursor()) {
                    GatewayCursorCodec.decodeInstanceEventCursor(request.afterCursor).also {
                        require(it.instanceId == instanceId.toString()) {
                            "after_cursor does not match instance_id"
                        }
                    }.sequence
                } else {
                    null
                }

                val result = instanceEventQueryService.listEvents(
                    InstanceEventFilter(
                        instanceId = instanceId,
                        afterSequenceExclusive = afterSequence,
                        pageSize = request.pageSize,
                    )
                )

                if (callObserver?.isCancelled == true) return@launch

                val builder = ListInstanceEventsResponse.newBuilder()
                    .addAllEvents(result.events.map(::toProtoInstanceEvent))

                result.nextCursor?.let {
                    builder.setNextCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(
                                instanceId = instanceId.toString(),
                                sequence = it,
                            )
                        )
                    )
                }

                responseObserver.onNext(builder.build())
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(toStatusException(t))
            }
        }
    }

    override fun watchInstanceEvents(
        request: WatchInstanceEventsRequest,
        responseObserver: StreamObserver<WatchInstanceEventsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        val callObserver = responseObserver as? ServerCallStreamObserver<WatchInstanceEventsResponse>

        scope.launch {
            try {
                val instanceId = parseInstanceUuid(request.instanceId, "instance_id")
                val afterSequence = if (request.hasAfterCursor()) {
                    GatewayCursorCodec.decodeInstanceEventCursor(request.afterCursor).also {
                        require(it.instanceId == instanceId.toString()) {
                            "after_cursor does not match instance_id"
                        }
                    }.sequence
                } else {
                    null
                }

                instanceEventStreamService.watch(instanceId, principal, afterSequence) { event ->
                    if (callObserver?.isCancelled == true) return@watch false

                    responseObserver.onNext(
                        WatchInstanceEventsResponse.newBuilder()
                            .setEvent(toProtoInstanceEvent(event))
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

    private fun parseDefinitionStatsFilter(
        request: ListDefinitionStatsRequest,
        principal: GatewayPrincipal,
    ): DefinitionStatsFilter {
        val namespace = optionalString(request.hasNamespace(), request.namespace)
        val timeFrom = requiredInstant(request.hasTimeFrom(), request.timeFrom, "time_from")
        val timeTo = requiredInstant(request.hasTimeTo(), request.timeTo, "time_to")
        validateTimeRange(timeFrom, timeTo)

        return DefinitionStatsFilter(
            namespace = namespace,
            name = optionalString(request.hasName(), request.name),
            version = optionalString(request.hasVersion(), request.version),
            allowedNamespaces = allowedNamespacesForRead(principal, namespace),
            timeFrom = timeFrom,
            timeTo = timeTo,
            pageSize = request.pageSize,
        )
    }

    private fun parseDefinitionStatsFilter(
        request: WatchDefinitionStatsRequest,
        principal: GatewayPrincipal,
    ): DefinitionStatsFilter {
        val namespace = optionalString(request.hasNamespace(), request.namespace)
        val timeFrom = requiredInstant(request.hasTimeFrom(), request.timeFrom, "time_from")
        val timeTo = requiredInstant(request.hasTimeTo(), request.timeTo, "time_to")
        validateTimeRange(timeFrom, timeTo)

        return DefinitionStatsFilter(
            namespace = namespace,
            name = optionalString(request.hasName(), request.name),
            version = optionalString(request.hasVersion(), request.version),
            allowedNamespaces = allowedNamespacesForRead(principal, namespace),
            timeFrom = timeFrom,
            timeTo = timeTo,
        )
    }

    private fun parseInstanceListFilter(
        request: ListInstancesRequest,
        principal: GatewayPrincipal,
    ): InstanceListFilter {
        val namespace = optionalString(request.hasNamespace(), request.namespace)
        return InstanceListFilter(
            namespace = namespace,
            name = optionalString(request.hasName(), request.name),
            version = optionalString(request.hasVersion(), request.version),
            status = optionalInstanceStatus(request.hasStatus(), request.status),
            createdFrom = optionalInstant(request.hasCreatedFrom(), request.createdFrom),
            createdTo = optionalInstant(request.hasCreatedTo(), request.createdTo),
            instanceIdPrefix = optionalString(request.hasInstanceIdPrefix(), request.instanceIdPrefix),
            allowedNamespaces = allowedNamespacesForRead(principal, namespace),
            pageSize = request.pageSize,
        ).also {
            validateCreatedRange(it.createdFrom, it.createdTo)
        }
    }

    private fun parseInstanceListFilter(
        request: WatchInstancesRequest,
        principal: GatewayPrincipal,
    ): InstanceListFilter {
        val namespace = optionalString(request.hasNamespace(), request.namespace)
        return InstanceListFilter(
            namespace = namespace,
            name = optionalString(request.hasName(), request.name),
            version = optionalString(request.hasVersion(), request.version),
            status = optionalInstanceStatus(request.hasStatus(), request.status),
            createdFrom = optionalInstant(request.hasCreatedFrom(), request.createdFrom),
            createdTo = optionalInstant(request.hasCreatedTo(), request.createdTo),
            instanceIdPrefix = optionalString(request.hasInstanceIdPrefix(), request.instanceIdPrefix),
            allowedNamespaces = allowedNamespacesForRead(principal, namespace),
        ).also {
            validateCreatedRange(it.createdFrom, it.createdTo)
        }
    }

    private suspend fun authorizeInstanceRead(principal: GatewayPrincipal, instanceId: UUID) {
        val snapshotSequence = instanceQueryService.latestSequence()
        val row = instanceQueryService.findInstance(instanceId, snapshotSequence)
        if (row != null) {
            authorizer.requireNamespace(principal, row.instance.namespace)
            return
        }

        val firstEvent = instanceEventQueryService.firstEvent(instanceId)
            ?: throw GatewayNotFoundException("Instance '$instanceId' not found")
        val namespace = firstEvent.namespace?.takeIf { it.isNotBlank() }
            ?: throw GatewayPermissionDeniedException(
                "Not authorized for instance '$instanceId': namespace is missing from lifecycle event"
            )
        authorizer.requireNamespace(principal, namespace)
    }

    private fun allowedNamespacesForRead(
        principal: GatewayPrincipal,
        explicitNamespace: String?,
    ): Set<String>? {
        explicitNamespace?.let {
            authorizer.requireNamespace(principal, it)
            return setOf(it)
        }

        if (!config.gatewayAuthenticationEnabled) return null
        if (principal.namespaces.contains("*")) return null
        return principal.namespaces
    }

    private fun requireDefinitionPageCursorMatches(
        cursor: DefinitionPageCursor,
        filter: DefinitionListFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "page_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "page_cursor does not match name filter" }
        require(cursor.latestOnly == filter.latestOnly) { "page_cursor does not match latest_only filter" }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "page_cursor does not match authorized namespaces"
        }
    }

    private fun requireDefinitionStatsPageCursorMatches(
        cursor: DefinitionStatsPageCursor,
        filter: DefinitionStatsFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "page_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "page_cursor does not match name filter" }
        require(cursor.version == filter.version) { "page_cursor does not match version filter" }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "page_cursor does not match authorized namespaces"
        }
        require(cursor.timeFrom == filter.timeFrom.toString()) { "page_cursor does not match time_from filter" }
        require(cursor.timeTo == filter.timeTo.toString()) { "page_cursor does not match time_to filter" }
    }

    private fun requireDefinitionStatsWatchCursorMatches(
        cursor: DefinitionStatsWatchCursor,
        filter: DefinitionStatsFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "after_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "after_cursor does not match name filter" }
        require(cursor.version == filter.version) { "after_cursor does not match version filter" }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "after_cursor does not match authorized namespaces"
        }
        require(cursor.timeFrom == filter.timeFrom.toString()) { "after_cursor does not match time_from filter" }
        require(cursor.timeTo == filter.timeTo.toString()) { "after_cursor does not match time_to filter" }
    }

    private fun requireInstancePageCursorMatches(
        cursor: InstancePageCursor,
        filter: InstanceListFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "page_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "page_cursor does not match name filter" }
        require(cursor.version == filter.version) { "page_cursor does not match version filter" }
        require(cursor.status == filter.status?.name) { "page_cursor does not match status filter" }
        require(cursor.createdFrom == filter.createdFrom?.toString()) { "page_cursor does not match created_from filter" }
        require(cursor.createdTo == filter.createdTo?.toString()) { "page_cursor does not match created_to filter" }
        require(cursor.instanceIdPrefix == filter.instanceIdPrefix) {
            "page_cursor does not match instance_id_prefix filter"
        }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "page_cursor does not match authorized namespaces"
        }
    }

    private fun requireInstanceWatchCursorMatches(
        cursor: InstanceWatchCursor,
        filter: InstanceListFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "after_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "after_cursor does not match name filter" }
        require(cursor.version == filter.version) { "after_cursor does not match version filter" }
        require(cursor.status == filter.status?.name) { "after_cursor does not match status filter" }
        require(cursor.createdFrom == filter.createdFrom?.toString()) { "after_cursor does not match created_from filter" }
        require(cursor.createdTo == filter.createdTo?.toString()) { "after_cursor does not match created_to filter" }
        require(cursor.instanceIdPrefix == filter.instanceIdPrefix) {
            "after_cursor does not match instance_id_prefix filter"
        }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "after_cursor does not match authorized namespaces"
        }
    }

    private fun normalizeAllowedNamespaces(namespaces: Set<String>?): List<String>? =
        namespaces?.toList()?.sorted()

    private fun toProtoDefinitionSummary(row: com.lemline.runner.gateway.dashboard.DefinitionSummaryRow): DefinitionSummary {
        val builder = DefinitionSummary.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setIsLatest(row.isLatest)
            .setVersionCount(row.versionCount)
            .setCreatedAt(toProtoTimestamp(row.createdAt))
        row.updatedAt?.let { builder.setUpdatedAt(toProtoTimestamp(it)) }
        return builder.build()
    }

    private fun toProtoWorkflowDefinition(row: DefinitionDetailsRow): WorkflowDefinition {
        val builder = WorkflowDefinition.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setSource(row.source)
            .addAllAvailableVersions(row.availableVersions)
            .setCreatedAt(toProtoTimestamp(row.createdAt))
        row.updatedAt?.let { builder.setUpdatedAt(toProtoTimestamp(it)) }
        return builder.build()
    }

    private fun toProtoDefinitionStats(row: DefinitionStatsRow): DefinitionStats =
        DefinitionStats.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setStartedCount(row.startedCount)
            .setCompletedCount(row.completedCount)
            .setFaultedCount(row.faultedCount)
            .setRunningCountAtTimeTo(row.runningCountAtTimeTo)
            .build()

    private fun toProtoDefinitionStatsChange(row: DefinitionStatsChangeRow): DefinitionStatsChange =
        DefinitionStatsChange.newBuilder()
            .setChangeType(
                when (row.changeType) {
                    ChangeType.UPSERT -> DefinitionStatsChangeType.DEFINITION_STATS_CHANGE_TYPE_UPSERT
                    ChangeType.REMOVED -> DefinitionStatsChangeType.DEFINITION_STATS_CHANGE_TYPE_REMOVED
                }
            )
            .setStats(toProtoDefinitionStats(row.stats))
            .build()

    private fun toProtoInstanceSummary(row: InstanceSummaryRow): InstanceSummary {
        val builder = InstanceSummary.newBuilder()
            .setInstanceId(row.instanceId.toString())
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setStatus(toProtoInstanceStatus(row.status))
            .setCreatedAt(toProtoTimestamp(row.createdAt))

        row.startedAt?.let { builder.setStartedAt(toProtoTimestamp(it)) }
        row.completedAt?.let { builder.setCompletedAt(toProtoTimestamp(it)) }
        row.faultedAt?.let { builder.setFaultedAt(toProtoTimestamp(it)) }
        row.durationMs?.let { builder.setDuration(toProtoDuration(it)) }
        row.error?.let { builder.setError(toProtoErrorSummary(it)) }
        return builder.build()
    }

    private fun toProtoInstanceChange(row: InstanceChangeRow): InstanceChange =
        InstanceChange.newBuilder()
            .setChangeType(
                when (row.changeType) {
                    ChangeType.UPSERT -> InstanceChangeType.INSTANCE_CHANGE_TYPE_UPSERT
                    ChangeType.REMOVED -> InstanceChangeType.INSTANCE_CHANGE_TYPE_REMOVED
                }
            )
            .setInstance(toProtoInstanceSummary(row.instance))
            .build()

    private fun toProtoInstanceEvent(row: InstanceEventRow): InstanceEvent {
        val builder = InstanceEvent.newBuilder()
            .setCursor(
                GatewayCursorCodec.encodeInstanceEventCursor(
                    InstanceEventCursor(
                        instanceId = row.instanceId.toString(),
                        sequence = row.cursor,
                    )
                )
            )
            .setInstanceId(row.instanceId.toString())
            .setEventType(row.eventType)
            .setCloudEventJson(row.cloudEventJson)
        row.eventTime?.let { builder.setEventTime(toProtoTimestamp(it)) }
        return builder.build()
    }

    private fun toProtoErrorSummary(row: ErrorSummaryRow): com.lemline.gateway.v1.ErrorSummary {
        val builder = com.lemline.gateway.v1.ErrorSummary.newBuilder()
            .setType(row.type)
            .setTitle(row.title)
        row.status?.let { builder.setStatus(it) }
        return builder.build()
    }

    private fun toProtoInstanceStatus(status: InstanceStatus): ProtoInstanceStatus =
        when (status) {
            InstanceStatus.PENDING -> ProtoInstanceStatus.INSTANCE_STATUS_PENDING
            InstanceStatus.RUNNING -> ProtoInstanceStatus.INSTANCE_STATUS_RUNNING
            InstanceStatus.COMPLETED -> ProtoInstanceStatus.INSTANCE_STATUS_COMPLETED
            InstanceStatus.FAULTED -> ProtoInstanceStatus.INSTANCE_STATUS_FAULTED
        }

    private fun toProtoStartInstanceResult(result: InstanceStartResult): StartInstanceResult =
        when (result.result) {
            StartInstanceOutcome.ACCEPTED_NEW -> StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_NEW
            StartInstanceOutcome.ACCEPTED_EXISTING -> StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_EXISTING
        }

    private fun toProtoTimestamp(value: Instant): ProtoTimestamp =
        ProtoTimestamp.newBuilder()
            .setSeconds(value.epochSecond)
            .setNanos(value.nano)
            .build()

    private fun toProtoDuration(valueMillis: Long): ProtoDuration {
        val seconds = valueMillis / 1_000
        val nanos = ((valueMillis % 1_000) * 1_000_000).toInt()
        return ProtoDuration.newBuilder()
            .setSeconds(seconds)
            .setNanos(nanos)
            .build()
    }

    private fun requirePrincipal(responseObserver: StreamObserver<*>): GatewayPrincipal? =
        resolvePrincipalOrNull() ?: run {
            responseObserver.onError(
                Status.UNAUTHENTICATED.withDescription("Missing authenticated principal").asRuntimeException()
            )
            null
        }

    private fun resolvePrincipalOrNull(): GatewayPrincipal? =
        GatewayAuthContext.getOrNull()
            ?: if (!config.gatewayAuthenticationEnabled) GatewayPrincipal.dashboardAnonymous else null

    private fun requiredString(raw: String, field: String): String =
        raw.trim().ifBlank { throw GatewayBadRequestException("$field must be provided") }

    private fun optionalString(hasField: Boolean, raw: String): String? =
        if (!hasField) {
            null
        } else {
            raw.trim().ifBlank { null }
        }

    private fun parseInstanceUuid(raw: String, field: String): UUID {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) {
            throw GatewayBadRequestException("$field must be provided")
        }

        return try {
            IDV7.from(trimmed).value
        } catch (_: Exception) {
            throw GatewayBadRequestException("$field must be a valid UUIDv7 string")
        }
    }

    private fun optionalInstanceStatus(hasField: Boolean, raw: ProtoInstanceStatus): InstanceStatus? {
        if (!hasField) return null
        return when (raw) {
            ProtoInstanceStatus.INSTANCE_STATUS_PENDING -> InstanceStatus.PENDING
            ProtoInstanceStatus.INSTANCE_STATUS_RUNNING -> InstanceStatus.RUNNING
            ProtoInstanceStatus.INSTANCE_STATUS_COMPLETED -> InstanceStatus.COMPLETED
            ProtoInstanceStatus.INSTANCE_STATUS_FAULTED -> InstanceStatus.FAULTED
            ProtoInstanceStatus.INSTANCE_STATUS_UNSPECIFIED,
            ProtoInstanceStatus.UNRECOGNIZED -> throw GatewayBadRequestException(
                "status must be one of PENDING, RUNNING, COMPLETED, or FAULTED"
            )
        }
    }

    private fun requiredInstant(hasField: Boolean, raw: ProtoTimestamp, field: String): Instant {
        if (!hasField) {
            throw GatewayBadRequestException("$field must be provided")
        }
        return optionalInstant(true, raw) ?: throw GatewayBadRequestException("$field must be provided")
    }

    private fun optionalInstant(hasField: Boolean, raw: ProtoTimestamp): Instant? =
        if (!hasField) {
            null
        } else {
            Instant.ofEpochSecond(raw.seconds, raw.nanos.toLong())
        }

    private fun validateTimeRange(timeFrom: Instant, timeTo: Instant) {
        if (!timeFrom.isBefore(timeTo)) {
            throw GatewayBadRequestException("time_from must be before time_to")
        }
    }

    private fun validateCreatedRange(createdFrom: Instant?, createdTo: Instant?) {
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw GatewayBadRequestException("created_from must be before created_to")
        }
    }

    private fun parseCursorInstant(raw: String): Instant = try {
        Instant.parse(raw)
    } catch (_: Exception) {
        throw GatewayBadRequestException("Invalid cursor")
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

private fun DefinitionPageCursor.toBoundary(): DefinitionPageBoundary =
    DefinitionPageBoundary(
        namespace = lastNamespace,
        name = lastName,
        version = lastVersion,
    )

private fun DefinitionStatsPageCursor.toBoundary(): DefinitionPageBoundary =
    DefinitionPageBoundary(
        namespace = lastNamespace,
        name = lastName,
        version = lastVersion,
    )

private fun InstancePageCursor.toBoundary(): InstancePageBoundary =
    InstancePageBoundary(
        createdAt = Instant.parse(lastCreatedAt),
        instanceId = UUID.fromString(lastInstanceId),
    )
