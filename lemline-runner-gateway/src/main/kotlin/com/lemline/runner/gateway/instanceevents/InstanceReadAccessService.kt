// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.instanceevents

import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.instances.InstanceQueryService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.util.UUID

@ApplicationScoped
class InstanceReadAccessService {

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var instanceQueryService: InstanceQueryService

    @Inject
    lateinit var instanceEventQueryService: InstanceEventQueryService

    suspend fun authorizeRead(principal: GatewayPrincipal, instanceId: UUID) {
        val latestSequence = instanceQueryService.latestLifecycleSequence()
        val instance = instanceQueryService.findInstance(instanceId, latestSequence)
        if (instance != null) {
            authorizer.requireNamespace(principal, instance.instance.namespace)
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
}
