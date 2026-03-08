// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class InstanceEventQueryService {

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    suspend fun listEvents(filter: InstanceEventFilter): InstanceEventListResult =
        instanceQueryService.listInstanceEvents(filter)

    suspend fun latestEventSequence(instanceId: UUID, snapshotSequence: Long? = null): Long? =
        instanceQueryService.latestInstanceEventSequence(instanceId, snapshotSequence)

    suspend fun firstEvent(instanceId: UUID): InstanceEventRow? =
        listEvents(InstanceEventFilter(instanceId = instanceId, pageSize = 1)).events.firstOrNull()
}
