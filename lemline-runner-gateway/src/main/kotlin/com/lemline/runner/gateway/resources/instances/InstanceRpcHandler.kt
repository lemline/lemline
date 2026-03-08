// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.gateway.v1.GetInstanceRequest
import com.lemline.gateway.v1.GetInstanceResponse
import com.lemline.gateway.v1.InstanceChange
import com.lemline.gateway.v1.InstanceChangeType
import com.lemline.gateway.v1.InstanceSummary
import com.lemline.gateway.v1.ListInstancesRequest
import com.lemline.gateway.v1.ListInstancesResponse
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.gateway.v1.StartInstanceResponse
import com.lemline.gateway.v1.StartInstanceResult
import com.lemline.gateway.v1.WatchInstancesRequest
import com.lemline.gateway.v1.WatchInstancesResponse
import com.lemline.gateway.v1.InstanceStatus as ProtoInstanceStatus
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.grpc.GatewayCursorCodec
import com.lemline.runner.gateway.grpc.InstanceEventCursor
import com.lemline.runner.gateway.grpc.InstancePageCursor
import com.lemline.runner.gateway.grpc.InstanceWatchCursor
import com.lemline.runner.gateway.grpc.allowedNamespacesForRead
import com.lemline.runner.gateway.grpc.normalizeAllowedNamespaces
import com.lemline.runner.gateway.grpc.optionalInstant
import com.lemline.runner.gateway.grpc.optionalInstanceStatus
import com.lemline.runner.gateway.grpc.optionalString
import com.lemline.runner.gateway.grpc.parseInstanceUuid
import com.lemline.runner.gateway.grpc.toBoundary
import com.lemline.runner.gateway.grpc.toProtoDuration
import com.lemline.runner.gateway.grpc.toProtoTimestamp
import com.lemline.runner.gateway.grpc.validateCreatedRange
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class InstanceRpcHandler(
    private val config: LemlineConfiguration,
) {

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var startService: InstanceStartService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    @Inject
    lateinit var instanceWatchService: InstanceWatchService

    suspend fun startInstance(
        request: StartInstanceRequest,
        principal: GatewayPrincipal,
    ): StartInstanceResponse {
        val result = startService.start(request, principal)
        return StartInstanceResponse.newBuilder()
            .setInstanceId(result.instanceId.toString())
            .setNamespace(result.namespace.toString())
            .setName(result.name.toString())
            .setVersion(result.version.toString())
            .setResult(toProtoStartInstanceResult(result))
            .build()
    }

    suspend fun getInstance(
        request: GetInstanceRequest,
        principal: GatewayPrincipal,
    ): GetInstanceResponse {
        val instanceId = parseInstanceUuid(request.instanceId, "instance_id")
        val snapshotSequence = instanceQueryService.latestLifecycleSequence()
        val row = instanceQueryService.findInstance(instanceId, snapshotSequence)
            ?: throw GatewayNotFoundException("Instance '$instanceId' not found")
        authorizer.requireNamespace(principal, row.instance.namespace)

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

        return builder.build()
    }

    suspend fun listInstances(
        request: ListInstancesRequest,
        principal: GatewayPrincipal,
    ): ListInstancesResponse {
        val filter = parseInstanceListFilter(request, principal)
        val pageCursor = if (request.hasPageCursor()) {
            GatewayCursorCodec.decodeInstancePageCursor(request.pageCursor).also {
                requireInstancePageCursorMatches(it, filter)
            }
        } else {
            null
        }
        val snapshotSequence = pageCursor?.snapshotSequence ?: instanceQueryService.latestLifecycleSequence()
        val result = instanceQueryService.listInstances(
            filter = filter,
            snapshotSequence = snapshotSequence,
            page = pageCursor?.toBoundary(),
        )

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

        return builder.build()
    }

    suspend fun watchInstances(
        request: WatchInstancesRequest,
        principal: GatewayPrincipal,
        emit: suspend (WatchInstancesResponse) -> Boolean,
    ) {
        authorizer.requireScope(principal, "lemline.watch")
        val filter = parseInstanceListFilter(request, principal)
        val afterSequence = if (request.hasAfterCursor()) {
            GatewayCursorCodec.decodeInstanceWatchCursor(request.afterCursor).also {
                requireInstanceWatchCursorMatches(it, filter)
            }.sequence
        } else {
            null
        }

        instanceWatchService.watch(filter, afterSequence) { change ->
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

            emit(
                WatchInstancesResponse.newBuilder()
                    .setCursor(cursor)
                    .setChange(toProtoInstanceChange(change))
                    .build()
            )
        }
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
            allowedNamespaces = allowedNamespacesForRead(config, authorizer, principal, namespace),
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
            allowedNamespaces = allowedNamespacesForRead(config, authorizer, principal, namespace),
        ).also {
            validateCreatedRange(it.createdFrom, it.createdTo)
        }
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
                    InstanceChangeKind.UPSERT -> InstanceChangeType.INSTANCE_CHANGE_TYPE_UPSERT
                    InstanceChangeKind.REMOVED -> InstanceChangeType.INSTANCE_CHANGE_TYPE_REMOVED
                }
            )
            .setInstance(toProtoInstanceSummary(row.instance))
            .build()

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
}
