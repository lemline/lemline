// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.lemline.gateway.v1.GetDefinitionRequest
import com.lemline.gateway.v1.GetDefinitionResponse
import com.lemline.gateway.v1.GetInstanceRequest
import com.lemline.gateway.v1.GetInstanceResponse
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
import com.lemline.gateway.v1.WatchDefinitionStatsRequest
import com.lemline.gateway.v1.WatchDefinitionStatsResponse
import com.lemline.gateway.v1.WatchInstanceEventsRequest
import com.lemline.gateway.v1.WatchInstanceEventsResponse
import com.lemline.gateway.v1.WatchInstancesRequest
import com.lemline.gateway.v1.WatchInstancesResponse
import com.lemline.gateway.v1.GatewayServiceGrpc
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.gatewayAuthenticationEnabled
import com.lemline.runner.gateway.auth.GatewayAuthContext
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.resources.definitions.DefinitionRpcHandler
import com.lemline.runner.gateway.resources.definitionstats.DefinitionStatsRpcHandler
import com.lemline.runner.gateway.resources.instanceevents.InstanceEventRpcHandler
import com.lemline.runner.gateway.resources.instances.InstanceRpcHandler
import io.grpc.Status
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import io.quarkus.grpc.GrpcService
import io.quarkus.runtime.ShutdownEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@GrpcService
@Singleton
class GatewayGrpcService(
    private val config: LemlineConfiguration,
) : GatewayServiceGrpc.GatewayServiceImplBase() {

    @Inject
    lateinit var definitionRpcHandler: DefinitionRpcHandler

    @Inject
    lateinit var definitionStatsRpcHandler: DefinitionStatsRpcHandler

    @Inject
    lateinit var instanceRpcHandler: InstanceRpcHandler

    @Inject
    lateinit var instanceEventRpcHandler: InstanceEventRpcHandler

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun listDefinitions(
        request: ListDefinitionsRequest,
        responseObserver: StreamObserver<ListDefinitionsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            definitionRpcHandler.listDefinitions(request, principal)
        }
    }

    override fun getDefinition(
        request: GetDefinitionRequest,
        responseObserver: StreamObserver<GetDefinitionResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            definitionRpcHandler.getDefinition(request, principal)
        }
    }

    override fun listDefinitionStats(
        request: ListDefinitionStatsRequest,
        responseObserver: StreamObserver<ListDefinitionStatsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            definitionStatsRpcHandler.listDefinitionStats(request, principal)
        }
    }

    override fun watchDefinitionStats(
        request: WatchDefinitionStatsRequest,
        responseObserver: StreamObserver<WatchDefinitionStatsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchStreaming(responseObserver) { emit ->
            definitionStatsRpcHandler.watchDefinitionStats(request, principal, emit)
        }
    }

    override fun startInstance(
        request: StartInstanceRequest,
        responseObserver: StreamObserver<StartInstanceResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            instanceRpcHandler.startInstance(request, principal)
        }
    }

    override fun getInstance(
        request: GetInstanceRequest,
        responseObserver: StreamObserver<GetInstanceResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            instanceRpcHandler.getInstance(request, principal)
        }
    }

    override fun listInstances(
        request: ListInstancesRequest,
        responseObserver: StreamObserver<ListInstancesResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            instanceRpcHandler.listInstances(request, principal)
        }
    }

    override fun watchInstances(
        request: WatchInstancesRequest,
        responseObserver: StreamObserver<WatchInstancesResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchStreaming(responseObserver) { emit ->
            instanceRpcHandler.watchInstances(request, principal, emit)
        }
    }

    override fun listInstanceEvents(
        request: ListInstanceEventsRequest,
        responseObserver: StreamObserver<ListInstanceEventsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchUnary(responseObserver) {
            instanceEventRpcHandler.listInstanceEvents(request, principal)
        }
    }

    override fun watchInstanceEvents(
        request: WatchInstanceEventsRequest,
        responseObserver: StreamObserver<WatchInstanceEventsResponse>,
    ) {
        val principal = requirePrincipal(responseObserver) ?: return
        launchStreaming(responseObserver) { emit ->
            instanceEventRpcHandler.watchInstanceEvents(request, principal, emit)
        }
    }

    private fun <T> launchUnary(
        responseObserver: StreamObserver<T>,
        block: suspend () -> T,
    ) {
        val callObserver = responseObserver as? ServerCallStreamObserver<T>
        scope.launch {
            try {
                val response = block()
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onNext(response)
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(GatewayStatusMapper.toStatusException(t))
            }
        }
    }

    private fun <T> launchStreaming(
        responseObserver: StreamObserver<T>,
        block: suspend (emit: suspend (T) -> Boolean) -> Unit,
    ) {
        val callObserver = responseObserver as? ServerCallStreamObserver<T>
        scope.launch {
            try {
                block { value ->
                    if (callObserver?.isCancelled == true) {
                        false
                    } else {
                        responseObserver.onNext(value)
                        true
                    }
                }
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onCompleted()
            } catch (t: Throwable) {
                if (callObserver?.isCancelled == true) return@launch
                responseObserver.onError(GatewayStatusMapper.toStatusException(t))
            }
        }
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
            ?: if (!config.gatewayAuthenticationEnabled) GatewayPrincipal.anonymous else null

    @Suppress("unused")
    fun onShutdown(@Observes event: ShutdownEvent) {
        scope.cancel("Gateway shutting down")
    }
}
