// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.instanceevents

import com.lemline.gateway.v1.InstanceEvent
import com.lemline.gateway.v1.ListInstanceEventsRequest
import com.lemline.gateway.v1.ListInstanceEventsResponse
import com.lemline.gateway.v1.WatchInstanceEventsRequest
import com.lemline.gateway.v1.WatchInstanceEventsResponse
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.grpc.GatewayCursorCodec
import com.lemline.runner.gateway.grpc.InstanceEventCursor
import com.lemline.runner.gateway.grpc.parseInstanceUuid
import com.lemline.runner.gateway.grpc.toProtoTimestamp
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@ApplicationScoped
class InstanceEventRpcHandler {

    @Inject
    lateinit var instanceReadAccessService: InstanceReadAccessService

    @Inject
    lateinit var instanceEventQueryService: InstanceEventQueryService

    @Inject
    lateinit var instanceEventWatchService: InstanceEventWatchService

    suspend fun listInstanceEvents(
        request: ListInstanceEventsRequest,
        principal: GatewayPrincipal,
    ): ListInstanceEventsResponse {
        val instanceId = parseInstanceUuid(request.instanceId, "instance_id")
        instanceReadAccessService.authorizeRead(principal, instanceId)

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

        return builder.build()
    }

    suspend fun watchInstanceEvents(
        request: WatchInstanceEventsRequest,
        principal: GatewayPrincipal,
        emit: suspend (WatchInstanceEventsResponse) -> Boolean,
    ) {
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

        instanceEventWatchService.watch(instanceId, principal, afterSequence) { event ->
            emit(
                WatchInstanceEventsResponse.newBuilder()
                    .setEvent(toProtoInstanceEvent(event))
                    .build()
            )
        }
    }

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
}
