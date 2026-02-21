// SPDX-License-Identifier: BUSL-1.1
package com.lemline.gateway.grpc

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.gateway.analytics.WorkflowWatchService
import com.lemline.gateway.auth.GatewayAuthContext
import com.lemline.gateway.errors.GatewayBadRequestException
import com.lemline.gateway.errors.GatewayConflictException
import com.lemline.gateway.errors.GatewayNotFoundException
import com.lemline.gateway.errors.GatewayPermissionDeniedException
import com.lemline.gateway.start.GatewayStartResult
import com.lemline.gateway.start.WorkflowStartService
import com.lemline.gateway.v1.StartWorkflowRequest
import com.lemline.gateway.v1.StartWorkflowResponse
import com.lemline.gateway.v1.WatchWorkflowRequest
import com.lemline.gateway.v1.WorkflowAnalyticsEvent
import com.lemline.gateway.v1.WorkflowGatewayGrpc
import io.grpc.Status
import io.grpc.StatusRuntimeException
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
class WorkflowGatewayGrpcService : WorkflowGatewayGrpc.WorkflowGatewayImplBase() {

    @Inject
    lateinit var startService: WorkflowStartService

    @Inject
    lateinit var watchService: WorkflowWatchService

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun startWorkflow(request: StartWorkflowRequest, responseObserver: StreamObserver<StartWorkflowResponse>) {
        val principal = GatewayAuthContext.getOrNull()
            ?: return responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Missing authenticated principal").asRuntimeException())

        val callObserver = responseObserver as? ServerCallStreamObserver<StartWorkflowResponse>
        scope.launch {
            try {
                val startResult = startService.start(request, principal)
                if (callObserver?.isCancelled == true) return@launch

                val response = StartWorkflowResponse.newBuilder()
                    .setWorkflowId(startResult.workflowId.toString())
                    .setVersion(startResult.version.toString())
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

    override fun watchWorkflow(request: WatchWorkflowRequest, responseObserver: StreamObserver<WorkflowAnalyticsEvent>) {
        val principal = GatewayAuthContext.getOrNull()
            ?: return responseObserver.onError(Status.UNAUTHENTICATED.withDescription("Missing authenticated principal").asRuntimeException())

        val callObserver = responseObserver as? ServerCallStreamObserver<WorkflowAnalyticsEvent>
        scope.launch {
            try {
                val workflowId = parseWorkflowId(request)

                watchService.watch(workflowId, principal) { event ->
                    if (callObserver?.isCancelled == true) return@watch false

                    responseObserver.onNext(
                        WorkflowAnalyticsEvent.newBuilder()
                            .setSequence(event.sequence)
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
