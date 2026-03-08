// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.definitionstats

import com.lemline.gateway.v1.DefinitionStats
import com.lemline.gateway.v1.DefinitionStatsChange
import com.lemline.gateway.v1.DefinitionStatsChangeType
import com.lemline.gateway.v1.ListDefinitionStatsRequest
import com.lemline.gateway.v1.ListDefinitionStatsResponse
import com.lemline.gateway.v1.WatchDefinitionStatsRequest
import com.lemline.gateway.v1.WatchDefinitionStatsResponse
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.grpc.DefinitionStatsPageCursor
import com.lemline.runner.gateway.grpc.DefinitionStatsWatchCursor
import com.lemline.runner.gateway.grpc.GatewayCursorCodec
import com.lemline.runner.gateway.grpc.allowedNamespacesForRead
import com.lemline.runner.gateway.grpc.normalizeAllowedNamespaces
import com.lemline.runner.gateway.grpc.optionalString
import com.lemline.runner.gateway.grpc.parseCursorInstant
import com.lemline.runner.gateway.grpc.requiredInstant
import com.lemline.runner.gateway.grpc.toBoundary
import com.lemline.runner.gateway.grpc.validateTimeRange
import com.lemline.runner.gateway.instances.InstanceQueryService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class DefinitionStatsRpcHandler(
    private val config: LemlineConfiguration,
) {

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var definitionStatsQueryService: DefinitionStatsQueryService

    @Inject
    lateinit var definitionStatsWatchService: DefinitionStatsWatchService

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    suspend fun listDefinitionStats(
        request: ListDefinitionStatsRequest,
        principal: GatewayPrincipal,
    ): ListDefinitionStatsResponse {
        val filter = parseDefinitionStatsFilter(request, principal)
        val pageCursor = if (request.hasPageCursor()) {
            GatewayCursorCodec.decodeDefinitionStatsPageCursor(request.pageCursor).also {
                requireDefinitionStatsPageCursorMatches(it, filter)
            }
        } else {
            null
        }
        val snapshotSequence = pageCursor?.snapshotSequence ?: instanceQueryService.latestLifecycleSequence()
        val result = definitionStatsQueryService.listDefinitionStats(
            filter = filter,
            snapshotSequence = snapshotSequence,
            page = pageCursor?.toBoundary(),
        )

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

        return builder.build()
    }

    suspend fun watchDefinitionStats(
        request: WatchDefinitionStatsRequest,
        principal: GatewayPrincipal,
        emit: suspend (WatchDefinitionStatsResponse) -> Boolean,
    ) {
        authorizer.requireScope(principal, "lemline.watch")
        val filter = parseDefinitionStatsFilter(request, principal)
        val afterSequence = if (request.hasAfterCursor()) {
            GatewayCursorCodec.decodeDefinitionStatsWatchCursor(request.afterCursor).also {
                requireDefinitionStatsWatchCursorMatches(it, filter)
            }.sequence
        } else {
            null
        }

        definitionStatsWatchService.watch(filter, afterSequence) { change ->
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

            emit(
                WatchDefinitionStatsResponse.newBuilder()
                    .setCursor(cursor)
                    .setChange(toProtoDefinitionStatsChange(change))
                    .build()
            )
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
            allowedNamespaces = allowedNamespacesForRead(config, authorizer, principal, namespace),
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
            allowedNamespaces = allowedNamespacesForRead(config, authorizer, principal, namespace),
            timeFrom = timeFrom,
            timeTo = timeTo,
        )
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
                    DefinitionStatsChangeKind.UPSERT -> DefinitionStatsChangeType.DEFINITION_STATS_CHANGE_TYPE_UPSERT
                    DefinitionStatsChangeKind.REMOVED -> DefinitionStatsChangeType.DEFINITION_STATS_CHANGE_TYPE_REMOVED
                }
            )
            .setStats(toProtoDefinitionStats(row.stats))
            .build()
}
