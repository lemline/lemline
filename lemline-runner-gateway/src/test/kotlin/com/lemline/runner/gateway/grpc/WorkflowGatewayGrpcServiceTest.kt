// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.lemline.gateway.v1.ListDefinitionsRequest
import com.lemline.gateway.v1.ListDefinitionsResponse
import com.lemline.gateway.v1.WatchInstanceEventsRequest
import com.lemline.gateway.v1.WatchInstanceEventsResponse
import com.lemline.runner.gateway.auth.GatewayAuthContext
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.definitionstats.DefinitionStatsRpcHandler
import com.lemline.runner.gateway.definitions.DefinitionRpcHandler
import com.lemline.runner.gateway.instanceevents.InstanceEventRpcHandler
import com.lemline.runner.gateway.instances.InstanceRpcHandler
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.grpc.Status
import io.grpc.stub.StreamObserver
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WorkflowGatewayGrpcService")
class WorkflowGatewayGrpcServiceTest {

    @Test
    fun `missing principal returns unauthenticated`() {
        val service = newService(testLemlineConfiguration(authenticationEnabled = true))
        val observer = TestObserver<ListDefinitionsResponse>()

        service.listDefinitions(ListDefinitionsRequest.getDefaultInstance(), observer)

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.UNAUTHENTICATED.code
        status.description shouldBe "Missing authenticated principal"
    }

    @Test
    fun `authentication disabled falls back to anonymous principal`() {
        val config = testLemlineConfiguration(authenticationEnabled = false)
        val definitionRpcHandler = mockk<DefinitionRpcHandler>()
        val service = newService(config, definitionRpcHandler = definitionRpcHandler)

        val response = ListDefinitionsResponse.newBuilder().setTotalCount(1).build()
        coEvery {
            definitionRpcHandler.listDefinitions(any(), GatewayPrincipal.anonymous)
        } returns response

        val observer = TestObserver<ListDefinitionsResponse>()
        service.listDefinitions(ListDefinitionsRequest.getDefaultInstance(), observer)

        observer.await()
        observer.error shouldBe null
        observer.values.single() shouldBe response
    }

    @Test
    fun `listDefinitions delegates unary call to definition handler`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val definitionRpcHandler = mockk<DefinitionRpcHandler>()
        val service = newService(config, definitionRpcHandler = definitionRpcHandler)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )
        val request = ListDefinitionsRequest.newBuilder().setNamespace("team-a").build()
        val response = ListDefinitionsResponse.newBuilder().setTotalCount(2).build()

        coEvery { definitionRpcHandler.listDefinitions(request, principal) } returns response

        val observer = TestObserver<ListDefinitionsResponse>()
        withPrincipal(principal) {
            service.listDefinitions(request, observer)
        }

        observer.await()
        observer.error shouldBe null
        observer.values.single() shouldBe response
        coVerify(exactly = 1) { definitionRpcHandler.listDefinitions(request, principal) }
    }

    @Test
    fun `watchInstanceEvents forwards streamed messages from instance event handler`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val instanceEventRpcHandler = mockk<InstanceEventRpcHandler>()
        val service = newService(config, instanceEventRpcHandler = instanceEventRpcHandler)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("*"),
        )
        val request = WatchInstanceEventsRequest.newBuilder()
            .setInstanceId("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
            .build()
        val first = WatchInstanceEventsResponse.getDefaultInstance()
        val second = WatchInstanceEventsResponse.newBuilder().build()

        coEvery { instanceEventRpcHandler.watchInstanceEvents(request, principal, any()) } coAnswers {
            val emit = thirdArg<suspend (WatchInstanceEventsResponse) -> Boolean>()
            runBlocking {
                emit(first)
                emit(second)
            }
        }

        val observer = TestObserver<WatchInstanceEventsResponse>()
        withPrincipal(principal) {
            service.watchInstanceEvents(request, observer)
        }

        observer.await()
        observer.error shouldBe null
        observer.values.shouldHaveSize(2)
        observer.values[0] shouldBe first
        observer.values[1] shouldBe second
    }

    private fun newService(
        config: com.lemline.runner.config.LemlineConfiguration,
        definitionRpcHandler: DefinitionRpcHandler = mockk(relaxed = true),
        definitionStatsRpcHandler: DefinitionStatsRpcHandler = mockk(relaxed = true),
        instanceRpcHandler: InstanceRpcHandler = mockk(relaxed = true),
        instanceEventRpcHandler: InstanceEventRpcHandler = mockk(relaxed = true),
    ) = WorkflowGatewayGrpcService(config).apply {
        this.definitionRpcHandler = definitionRpcHandler
        this.definitionStatsRpcHandler = definitionStatsRpcHandler
        this.instanceRpcHandler = instanceRpcHandler
        this.instanceEventRpcHandler = instanceEventRpcHandler
    }

    private fun withPrincipal(principal: GatewayPrincipal, block: () -> Unit) {
        val context = GatewayAuthContext.set(principal)
        val previous = context.attach()
        try {
            block()
        } finally {
            context.detach(previous)
        }
    }

    private class TestObserver<T> : StreamObserver<T> {
        val values = mutableListOf<T>()
        var error: Throwable? = null
        private val done = CountDownLatch(1)

        override fun onNext(value: T) {
            values += value
        }

        override fun onError(t: Throwable) {
            error = t
            done.countDown()
        }

        override fun onCompleted() {
            done.countDown()
        }

        fun await() {
            done.await(2, TimeUnit.SECONDS) shouldBe true
        }
    }
}
