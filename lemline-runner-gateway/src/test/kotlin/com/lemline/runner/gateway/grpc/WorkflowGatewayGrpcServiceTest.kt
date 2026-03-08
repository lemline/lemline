// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.google.protobuf.Timestamp
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.runner.gateway.auth.GatewayAuthContext
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.config.testLemlineConfiguration
import com.lemline.runner.gateway.dashboard.ChangeType
import com.lemline.runner.gateway.dashboard.DefinitionDetailsRow
import com.lemline.runner.gateway.dashboard.DefinitionListFilter
import com.lemline.runner.gateway.dashboard.DefinitionListResult
import com.lemline.runner.gateway.dashboard.DefinitionPageBoundary
import com.lemline.runner.gateway.dashboard.DefinitionStatsChangeRow
import com.lemline.runner.gateway.dashboard.DefinitionStatsFilter
import com.lemline.runner.gateway.dashboard.DefinitionStatsListResult
import com.lemline.runner.gateway.dashboard.DefinitionStatsRow
import com.lemline.runner.gateway.dashboard.DefinitionSummaryRow
import com.lemline.runner.gateway.dashboard.ErrorSummaryRow
import com.lemline.runner.gateway.dashboard.InstanceChangeRow
import com.lemline.runner.gateway.dashboard.InstanceEventFilter
import com.lemline.runner.gateway.dashboard.InstanceEventListResult
import com.lemline.runner.gateway.dashboard.InstanceEventQueryService
import com.lemline.runner.gateway.dashboard.InstanceEventRow
import com.lemline.runner.gateway.dashboard.InstanceLookupRow
import com.lemline.runner.gateway.dashboard.InstanceListFilter
import com.lemline.runner.gateway.dashboard.InstanceListResult
import com.lemline.runner.gateway.dashboard.InstancePageBoundary
import com.lemline.runner.gateway.dashboard.InstanceQueryService
import com.lemline.runner.gateway.dashboard.InstanceStatus
import com.lemline.runner.gateway.dashboard.InstanceStreamService
import com.lemline.runner.gateway.dashboard.InstanceSummaryRow
import com.lemline.runner.gateway.dashboard.DefinitionQueryService
import com.lemline.runner.gateway.dashboard.StatsQueryService
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.errors.GatewayPermissionDeniedException
import com.lemline.runner.gateway.start.InstanceStartResult
import com.lemline.runner.gateway.start.InstanceStartService
import com.lemline.runner.gateway.start.StartInstanceOutcome
import com.lemline.runner.gateway.watch.InstanceEventStreamService
import io.grpc.Status
import io.grpc.stub.StreamObserver
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("WorkflowGatewayGrpcService")
class WorkflowGatewayGrpcServiceTest {

    @Test
    fun `listDefinitions allows read access without scopes`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val definitionQueryService = mockk<DefinitionQueryService>()
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer, definitionQueryService = definitionQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        coEvery {
            definitionQueryService.listDefinitions(
                filter = DefinitionListFilter(
                    namespace = "team-a",
                    name = null,
                    latestOnly = true,
                    allowedNamespaces = setOf("team-a"),
                    pageSize = 0,
                ),
                snapshotAt = any(),
                page = null,
            )
        } returns DefinitionListResult(
            definitions = listOf(
                DefinitionSummaryRow(
                    namespace = "team-a",
                    name = "orders",
                    version = "1.0.0",
                    isLatest = true,
                    versionCount = 1,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = null,
                )
            ),
            nextPage = null,
            totalCount = 1L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListDefinitionsResponse>()
        withPrincipal(principal) {
            service.listDefinitions(
                com.lemline.gateway.v1.ListDefinitionsRequest.newBuilder()
                    .setNamespace("team-a")
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        observer.values.shouldHaveSize(1)
        observer.values.single().definitionsCount shouldBe 1
    }

    @Test
    fun `listDefinitions maps next page cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val definitionQueryService = mockk<DefinitionQueryService>()
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer, definitionQueryService = definitionQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        coEvery {
            definitionQueryService.listDefinitions(
                filter = DefinitionListFilter(
                    namespace = "team-a",
                    name = "orders",
                    latestOnly = false,
                    allowedNamespaces = setOf("team-a"),
                    pageSize = 10,
                ),
                snapshotAt = any(),
                page = null,
            )
        } returns DefinitionListResult(
            definitions = listOf(
                DefinitionSummaryRow(
                    namespace = "team-a",
                    name = "orders",
                    version = "1.1.0",
                    isLatest = true,
                    versionCount = 2,
                    createdAt = Instant.parse("2026-01-02T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-03T00:00:00Z"),
                )
            ),
            nextPage = DefinitionPageBoundary(
                namespace = "team-a",
                name = "orders",
                version = "1.0.0",
            ),
            totalCount = 2L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListDefinitionsResponse>()
        withPrincipal(principal) {
            service.listDefinitions(
                com.lemline.gateway.v1.ListDefinitionsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setLatestOnly(false)
                    .setPageSize(10)
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.totalCount shouldBe 2L
        val nextPage = GatewayCursorCodec.decodeDefinitionPageCursor(response.nextPageCursor)
        nextPage.namespace shouldBe "team-a"
        nextPage.name shouldBe "orders"
        nextPage.latestOnly shouldBe false
        nextPage.lastVersion shouldBe "1.0.0"
        nextPage.allowedNamespaces shouldBe listOf("team-a")
    }

    @Test
    fun `listDefinitions rejects mismatched page cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListDefinitionsResponse>()
        withPrincipal(principal) {
            service.listDefinitions(
                com.lemline.gateway.v1.ListDefinitionsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setPageCursor(
                        GatewayCursorCodec.encodeDefinitionPageCursor(
                            DefinitionPageCursor(
                                namespace = "team-a",
                                name = "payments",
                                latestOnly = true,
                                allowedNamespaces = listOf("team-a"),
                                snapshotAt = "2026-01-01T00:00:00Z",
                                lastNamespace = "team-a",
                                lastName = "payments",
                                lastVersion = "1.0.0",
                            )
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldContain "page_cursor does not match name filter"
    }

    @Test
    fun `getDefinition maps workflow definition and available versions`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val definitionQueryService = mockk<DefinitionQueryService>()
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer, definitionQueryService = definitionQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        coEvery {
            definitionQueryService.getDefinition("team-a", "orders", "1.1.0")
        } returns DefinitionDetailsRow(
            namespace = "team-a",
            name = "orders",
            version = "1.1.0",
            source = """{"id":"orders"}""",
            availableVersions = listOf("1.0.0", "1.1.0"),
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-02T00:00:00Z"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.GetDefinitionResponse>()
        withPrincipal(principal) {
            service.getDefinition(
                com.lemline.gateway.v1.GetDefinitionRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setVersion("1.1.0")
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val definition = observer.values.single().definition
        definition.namespace shouldBe "team-a"
        definition.name shouldBe "orders"
        definition.version shouldBe "1.1.0"
        definition.source shouldBe """{"id":"orders"}"""
        definition.availableVersionsList shouldBe listOf("1.0.0", "1.1.0")
    }

    @Test
    fun `getDefinition rejects unauthorized namespace`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.GetDefinitionResponse>()
        withPrincipal(principal) {
            service.getDefinition(
                com.lemline.gateway.v1.GetDefinitionRequest.newBuilder()
                    .setNamespace("team-b")
                    .setName("orders")
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.PERMISSION_DENIED.code
    }

    @Test
    fun `listDefinitionStats maps stats rows and cursors`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val statsQueryService = mockk<StatsQueryService>()
        val instanceQueryService = mockk<InstanceQueryService>()
        val service = newService(
            config,
            authorizer = authorizer,
            statsQueryService = statsQueryService,
            instanceQueryService = instanceQueryService,
        )
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )
        val timeFrom = Instant.parse("2026-01-01T00:00:00Z")
        val timeTo = Instant.parse("2026-01-02T00:00:00Z")

        coEvery { instanceQueryService.latestSequence() } returns 120L
        coEvery {
            statsQueryService.listDefinitionStats(
                filter = DefinitionStatsFilter(
                    namespace = "team-a",
                    name = "orders",
                    version = "1.0.0",
                    allowedNamespaces = setOf("team-a"),
                    timeFrom = timeFrom,
                    timeTo = timeTo,
                    pageSize = 25,
                ),
                snapshotSequence = 120L,
                page = null,
            )
        } returns DefinitionStatsListResult(
            stats = listOf(
                DefinitionStatsRow(
                    namespace = "team-a",
                    name = "orders",
                    version = "1.0.0",
                    startedCount = 10,
                    completedCount = 8,
                    faultedCount = 2,
                    runningCountAtTimeTo = 1,
                )
            ),
            nextPage = DefinitionPageBoundary(
                namespace = "team-a",
                name = "orders",
                version = "0.9.0",
            ),
            totalCount = 1L,
            watchSequence = 125L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListDefinitionStatsResponse>()
        withPrincipal(principal) {
            service.listDefinitionStats(
                com.lemline.gateway.v1.ListDefinitionStatsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setVersion("1.0.0")
                    .setTimeFrom(ts(timeFrom.toString()))
                    .setTimeTo(ts(timeTo.toString()))
                    .setPageSize(25)
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.statsCount shouldBe 1
        response.statsList.single().runningCountAtTimeTo shouldBe 1L
        GatewayCursorCodec.decodeDefinitionStatsWatchCursor(response.watchCursor).sequence shouldBe 125L
        val nextPage = GatewayCursorCodec.decodeDefinitionStatsPageCursor(response.nextPageCursor)
        nextPage.snapshotSequence shouldBe 125L
        nextPage.lastVersion shouldBe "0.9.0"
    }

    @Test
    fun `listDefinitionStats rejects invalid time range`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListDefinitionStatsResponse>()
        withPrincipal(principal) {
            service.listDefinitionStats(
                com.lemline.gateway.v1.ListDefinitionStatsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setTimeFrom(ts("2026-01-02T00:00:00Z"))
                    .setTimeTo(ts("2026-01-01T00:00:00Z"))
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldBe "time_from must be before time_to"
    }

    @Test
    fun `watchInstances requires lemline watch scope`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.WatchInstancesResponse>()
        withPrincipal(principal) {
            service.watchInstances(
                com.lemline.gateway.v1.WatchInstancesRequest.newBuilder().build(),
                observer,
            )
        }

        observer.await()
        Status.fromThrowable(observer.error).code shouldBe Status.PERMISSION_DENIED.code
    }

    @Test
    fun `watchDefinitionStats requires lemline watch scope`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.WatchDefinitionStatsResponse>()
        withPrincipal(principal) {
            service.watchDefinitionStats(
                com.lemline.gateway.v1.WatchDefinitionStatsRequest.newBuilder()
                    .setTimeFrom(ts("2026-01-01T00:00:00Z"))
                    .setTimeTo(ts("2026-01-02T00:00:00Z"))
                    .build(),
                observer,
            )
        }

        observer.await()
        Status.fromThrowable(observer.error).code shouldBe Status.PERMISSION_DENIED.code
    }

    @Test
    fun `watchInstances maps change payload and cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceStreamService = mockk<InstanceStreamService>()
        val service = newService(config, authorizer = authorizer, instanceStreamService = instanceStreamService)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("*"),
        )
        val filter = InstanceListFilter(
            namespace = "team-a",
            name = "orders",
            version = null,
            status = null,
            createdFrom = null,
            createdTo = null,
            instanceIdPrefix = null,
            allowedNamespaces = setOf("team-a"),
        )
        val change = InstanceChangeRow(
            cursor = 42L,
            changeType = ChangeType.REMOVED,
            instance = instanceSummaryRow(
                instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31"),
                status = InstanceStatus.COMPLETED,
                completedAt = Instant.parse("2026-01-01T00:01:00Z"),
                durationMs = 60_000L,
            ),
        )

        coEvery {
            instanceStreamService.watchInstances(filter, 9L, any())
        } coAnswers {
            val callback = arg<suspend (InstanceChangeRow) -> Boolean>(2)
            runBlocking { callback(change) }
        }

        val afterCursor = GatewayCursorCodec.encodeInstanceWatchCursor(
            InstanceWatchCursor(
                namespace = "team-a",
                name = "orders",
                version = null,
                status = null,
                createdFrom = null,
                createdTo = null,
                instanceIdPrefix = null,
                allowedNamespaces = listOf("team-a"),
                sequence = 9L,
            )
        )
        val observer = TestObserver<com.lemline.gateway.v1.WatchInstancesResponse>()
        withPrincipal(principal) {
            service.watchInstances(
                com.lemline.gateway.v1.WatchInstancesRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setAfterCursor(afterCursor)
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.change.changeType shouldBe com.lemline.gateway.v1.InstanceChangeType.INSTANCE_CHANGE_TYPE_REMOVED
        GatewayCursorCodec.decodeInstanceWatchCursor(response.cursor).sequence shouldBe 42L
    }

    @Test
    fun `startInstance maps accepted existing result`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val startService = mockk<InstanceStartService>()
        val service = newService(config, startService = startService)
        val principal = GatewayPrincipal(
            subject = "starter",
            scopes = setOf("lemline.start"),
            namespaces = setOf("*"),
        )
        val startResult = InstanceStartResult(
            instanceId = WorkflowId.random(),
            namespace = WorkflowNamespace("team-a"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("1.0.0"),
            result = StartInstanceOutcome.ACCEPTED_EXISTING,
        )

        coEvery { startService.start(any(), principal) } returns startResult

        val observer = TestObserver<com.lemline.gateway.v1.StartInstanceResponse>()
        withPrincipal(principal) {
            service.startInstance(
                com.lemline.gateway.v1.StartInstanceRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setVersion("1.0.0")
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.instanceId shouldBe startResult.instanceId.toString()
        response.namespace shouldBe "team-a"
        response.name shouldBe "orders"
        response.version shouldBe "1.0.0"
        response.result shouldBe com.lemline.gateway.v1.StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_EXISTING
    }

    @Test
    fun `startInstance maps accepted new result`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val startService = mockk<InstanceStartService>()
        val service = newService(config, startService = startService)
        val principal = GatewayPrincipal(
            subject = "starter",
            scopes = setOf("lemline.start"),
            namespaces = setOf("*"),
        )
        val startResult = InstanceStartResult(
            instanceId = WorkflowId.random(),
            namespace = WorkflowNamespace("team-a"),
            name = WorkflowName("orders"),
            version = WorkflowVersion("1.2.0"),
            result = StartInstanceOutcome.ACCEPTED_NEW,
        )

        coEvery { startService.start(any(), principal) } returns startResult

        val observer = TestObserver<com.lemline.gateway.v1.StartInstanceResponse>()
        withPrincipal(principal) {
            service.startInstance(
                com.lemline.gateway.v1.StartInstanceRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        observer.values.single().result shouldBe
            com.lemline.gateway.v1.StartInstanceResult.START_INSTANCE_RESULT_ACCEPTED_NEW
    }

    @Test
    fun `startInstance maps conflicts to already exists`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val startService = mockk<InstanceStartService>()
        val service = newService(config, startService = startService)
        val principal = GatewayPrincipal(
            subject = "starter",
            scopes = setOf("lemline.start"),
            namespaces = setOf("*"),
        )

        coEvery { startService.start(any(), principal) } throws GatewayConflictException("already exists")

        val observer = TestObserver<com.lemline.gateway.v1.StartInstanceResponse>()
        withPrincipal(principal) {
            service.startInstance(
                com.lemline.gateway.v1.StartInstanceRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.ALREADY_EXISTS.code
        status.description shouldBe "already exists"
    }

    @Test
    fun `getInstance maps nested summary and latest event cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val service = newService(config, authorizer = authorizer, instanceQueryService = instanceQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 15L
        coEvery { instanceQueryService.findInstance(instanceId, 15L) } returns InstanceLookupRow(
            instance = InstanceSummaryRow(
                instanceId = instanceId,
                namespace = "team-a",
                name = "orders",
                version = "1.0.0",
                status = InstanceStatus.FAULTED,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                startedAt = Instant.parse("2026-01-01T00:00:01Z"),
                completedAt = null,
                faultedAt = Instant.parse("2026-01-01T00:00:05Z"),
                durationMs = 4_000L,
                error = com.lemline.runner.gateway.dashboard.ErrorSummaryRow(
                    type = "about:blank",
                    status = 500,
                    title = "boom",
                ),
            ),
            latestEventSequence = 15L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.GetInstanceResponse>()
        withPrincipal(principal) {
            service.getInstance(
                com.lemline.gateway.v1.GetInstanceRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.instance.instanceId shouldBe instanceId.toString()
        response.instance.status shouldBe com.lemline.gateway.v1.InstanceStatus.INSTANCE_STATUS_FAULTED
        response.instance.error.status shouldBe 500
        GatewayCursorCodec.decodeInstanceEventCursor(response.latestEventCursor).sequence shouldBe 15L
    }

    @Test
    fun `getInstance returns not found when missing`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val service = newService(config, authorizer = authorizer, instanceQueryService = instanceQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 15L
        coEvery { instanceQueryService.findInstance(instanceId, 15L) } returns null

        val observer = TestObserver<com.lemline.gateway.v1.GetInstanceResponse>()
        withPrincipal(principal) {
            service.getInstance(
                com.lemline.gateway.v1.GetInstanceRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.NOT_FOUND.code
        status.description shouldContain instanceId.toString()
    }

    @Test
    fun `listInstances maps rows and cursors`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val service = newService(config, authorizer = authorizer, instanceQueryService = instanceQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )
        val createdFrom = Instant.parse("2026-01-01T00:00:00Z")
        val createdTo = Instant.parse("2026-01-02T00:00:00Z")
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 240L
        coEvery {
            instanceQueryService.listInstances(
                filter = InstanceListFilter(
                    namespace = "team-a",
                    name = "orders",
                    version = "1.0.0",
                    status = InstanceStatus.RUNNING,
                    createdFrom = createdFrom,
                    createdTo = createdTo,
                    instanceIdPrefix = "018f40c4",
                    allowedNamespaces = setOf("team-a"),
                    pageSize = 25,
                ),
                snapshotSequence = 240L,
                page = null,
            )
        } returns InstanceListResult(
            instances = listOf(
                instanceSummaryRow(
                    instanceId = instanceId,
                    status = InstanceStatus.RUNNING,
                    startedAt = Instant.parse("2026-01-01T00:00:05Z"),
                    durationMs = 5_000L,
                )
            ),
            nextPage = InstancePageBoundary(
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                instanceId = instanceId,
            ),
            totalCount = 1L,
            watchSequence = 300L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListInstancesResponse>()
        withPrincipal(principal) {
            service.listInstances(
                com.lemline.gateway.v1.ListInstancesRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setVersion("1.0.0")
                    .setStatus(com.lemline.gateway.v1.InstanceStatus.INSTANCE_STATUS_RUNNING)
                    .setCreatedFrom(ts(createdFrom.toString()))
                    .setCreatedTo(ts(createdTo.toString()))
                    .setInstanceIdPrefix("018f40c4")
                    .setPageSize(25)
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.instancesCount shouldBe 1
        response.instancesList.single().status shouldBe com.lemline.gateway.v1.InstanceStatus.INSTANCE_STATUS_RUNNING
        GatewayCursorCodec.decodeInstanceWatchCursor(response.watchCursor).sequence shouldBe 300L
        val nextPage = GatewayCursorCodec.decodeInstancePageCursor(response.nextPageCursor)
        nextPage.snapshotSequence shouldBe 300L
        nextPage.lastInstanceId shouldBe instanceId.toString()
    }

    @Test
    fun `listInstances rejects invalid created range`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListInstancesResponse>()
        withPrincipal(principal) {
            service.listInstances(
                com.lemline.gateway.v1.ListInstancesRequest.newBuilder()
                    .setNamespace("team-a")
                    .setCreatedFrom(ts("2026-01-02T00:00:00Z"))
                    .setCreatedTo(ts("2026-01-01T00:00:00Z"))
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldBe "created_from must be before created_to"
    }

    @Test
    fun `watchDefinitionStats maps change payload and cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceStreamService = mockk<InstanceStreamService>()
        val service = newService(config, authorizer = authorizer, instanceStreamService = instanceStreamService)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("*"),
        )
        val filter = DefinitionStatsFilter(
            namespace = null,
            name = null,
            version = null,
            timeFrom = Instant.parse("2026-01-01T00:00:00Z"),
            timeTo = Instant.parse("2026-01-02T00:00:00Z"),
        )
        val change = DefinitionStatsChangeRow(
            cursor = 42L,
            changeType = ChangeType.UPSERT,
            stats = DefinitionStatsRow(
                namespace = "team-a",
                name = "orders",
                version = "1.0.0",
                startedCount = 10,
                completedCount = 9,
                faultedCount = 1,
                runningCountAtTimeTo = 0,
            ),
        )

        coEvery {
            instanceStreamService.watchDefinitionStats(filter, null, any())
        } coAnswers {
            val callback = arg<suspend (DefinitionStatsChangeRow) -> Boolean>(2)
            runBlocking { callback(change) }
        }

        val observer = TestObserver<com.lemline.gateway.v1.WatchDefinitionStatsResponse>()
        withPrincipal(principal) {
            service.watchDefinitionStats(
                com.lemline.gateway.v1.WatchDefinitionStatsRequest.newBuilder()
                    .setTimeFrom(ts("2026-01-01T00:00:00Z"))
                    .setTimeTo(ts("2026-01-02T00:00:00Z"))
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.change.changeType shouldBe com.lemline.gateway.v1.DefinitionStatsChangeType.DEFINITION_STATS_CHANGE_TYPE_UPSERT
        GatewayCursorCodec.decodeDefinitionStatsWatchCursor(response.cursor).sequence shouldBe 42L
    }

    @Test
    fun `watchDefinitionStats rejects mismatched after cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.WatchDefinitionStatsResponse>()
        withPrincipal(principal) {
            service.watchDefinitionStats(
                com.lemline.gateway.v1.WatchDefinitionStatsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setName("orders")
                    .setTimeFrom(ts("2026-01-01T00:00:00Z"))
                    .setTimeTo(ts("2026-01-02T00:00:00Z"))
                    .setAfterCursor(
                        GatewayCursorCodec.encodeDefinitionStatsWatchCursor(
                            DefinitionStatsWatchCursor(
                                namespace = "team-a",
                                name = "payments",
                                version = null,
                                allowedNamespaces = listOf("team-a"),
                                timeFrom = "2026-01-01T00:00:00Z",
                                timeTo = "2026-01-02T00:00:00Z",
                                sequence = 5L,
                            )
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldContain "after_cursor does not match name filter"
    }

    @Test
    fun `watchInstances rejects mismatched after cursor filters`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val service = newService(config, authorizer = authorizer)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("team-a"),
        )

        val observer = TestObserver<com.lemline.gateway.v1.WatchInstancesResponse>()
        withPrincipal(principal) {
            service.watchInstances(
                com.lemline.gateway.v1.WatchInstancesRequest.newBuilder()
                    .setNamespace("team-a")
                    .setCreatedFrom(ts("2026-01-01T00:00:00Z"))
                    .setAfterCursor(
                        GatewayCursorCodec.encodeInstanceWatchCursor(
                            InstanceWatchCursor(
                                namespace = "team-a",
                                name = null,
                                version = null,
                                status = null,
                                createdFrom = null,
                                createdTo = null,
                                instanceIdPrefix = null,
                                allowedNamespaces = listOf("team-a"),
                                sequence = 8L,
                            )
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldContain "after_cursor does not match created_from filter"
    }

    @Test
    fun `listInstanceEvents maps events and next cursor`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val instanceEventQueryService = mockk<InstanceEventQueryService>()
        val service = newService(
            config,
            authorizer = authorizer,
            instanceQueryService = instanceQueryService,
            instanceEventQueryService = instanceEventQueryService,
        )
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 11L
        coEvery { instanceQueryService.findInstance(instanceId, 11L) } returns InstanceLookupRow(
            instance = instanceSummaryRow(instanceId = instanceId),
            latestEventSequence = null,
        )
        coEvery {
            instanceEventQueryService.listEvents(
                InstanceEventFilter(
                    instanceId = instanceId,
                    afterSequenceExclusive = 5L,
                    pageSize = 2,
                )
            )
        } returns InstanceEventListResult(
            events = listOf(
                InstanceEventRow(
                    cursor = 6L,
                    instanceId = instanceId,
                    eventType = "com.lemline.workflow.completed",
                    eventTime = Instant.parse("2026-01-01T00:02:00Z"),
                    cloudEventJson = """{"type":"completed"}""",
                    namespace = "team-a",
                )
            ),
            nextCursor = 6L,
            latestCursor = 6L,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListInstanceEventsResponse>()
        withPrincipal(principal) {
            service.listInstanceEvents(
                com.lemline.gateway.v1.ListInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .setAfterCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(instanceId = instanceId.toString(), sequence = 5L)
                        )
                    )
                    .setPageSize(2)
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val response = observer.values.single()
        response.eventsCount shouldBe 1
        response.eventsList.single().eventType shouldBe "com.lemline.workflow.completed"
        GatewayCursorCodec.decodeInstanceEventCursor(response.eventsList.single().cursor).sequence shouldBe 6L
        GatewayCursorCodec.decodeInstanceEventCursor(response.nextCursor).sequence shouldBe 6L
    }

    @Test
    fun `listInstanceEvents rejects mismatched after cursor instance id`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val service = newService(config, authorizer = authorizer, instanceQueryService = instanceQueryService)
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("team-a"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 11L
        coEvery { instanceQueryService.findInstance(instanceId, 11L) } returns InstanceLookupRow(
            instance = instanceSummaryRow(instanceId = instanceId),
            latestEventSequence = null,
        )

        val observer = TestObserver<com.lemline.gateway.v1.ListInstanceEventsResponse>()
        withPrincipal(principal) {
            service.listInstanceEvents(
                com.lemline.gateway.v1.ListInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .setAfterCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(
                                instanceId = "018f40c4-5f0f-7bf2-b0f7-4a4931227c32",
                                sequence = 5L,
                            )
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldBe "after_cursor does not match instance_id"
    }

    @Test
    fun `listInstanceEvents returns not found when instance is unknown`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val authorizer = GatewayAuthorizer(config)
        val instanceQueryService = mockk<InstanceQueryService>()
        val instanceEventQueryService = mockk<InstanceEventQueryService>()
        val service = newService(
            config,
            authorizer = authorizer,
            instanceQueryService = instanceQueryService,
            instanceEventQueryService = instanceEventQueryService,
        )
        val principal = GatewayPrincipal(
            subject = "reader",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery { instanceQueryService.latestSequence() } returns 11L
        coEvery { instanceQueryService.findInstance(instanceId, 11L) } returns null
        coEvery { instanceEventQueryService.firstEvent(instanceId) } returns null

        val observer = TestObserver<com.lemline.gateway.v1.ListInstanceEventsResponse>()
        withPrincipal(principal) {
            service.listInstanceEvents(
                com.lemline.gateway.v1.ListInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.NOT_FOUND.code
        status.description shouldContain instanceId.toString()
    }

    @Test
    fun `watchInstanceEvents maps streamed event`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val instanceEventStreamService = mockk<InstanceEventStreamService>()
        val service = newService(config, instanceEventStreamService = instanceEventStreamService)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("*"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
        val event = InstanceEventRow(
            cursor = 6L,
            instanceId = instanceId,
            eventType = "com.lemline.workflow.started",
            eventTime = Instant.parse("2026-01-01T00:00:05Z"),
            cloudEventJson = """{"type":"started"}""",
            namespace = "team-a",
        )

        coEvery {
            instanceEventStreamService.watch(instanceId, principal, 5L, any())
        } coAnswers {
            val callback = arg<suspend (InstanceEventRow) -> Boolean>(3)
            runBlocking { callback(event) }
        }

        val observer = TestObserver<com.lemline.gateway.v1.WatchInstanceEventsResponse>()
        withPrincipal(principal) {
            service.watchInstanceEvents(
                com.lemline.gateway.v1.WatchInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .setAfterCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(instanceId = instanceId.toString(), sequence = 5L)
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        observer.error shouldBe null
        val streamedEvent = observer.values.single().event
        streamedEvent.eventType shouldBe "com.lemline.workflow.started"
        GatewayCursorCodec.decodeInstanceEventCursor(streamedEvent.cursor).sequence shouldBe 6L
    }

    @Test
    fun `watchInstanceEvents maps permission denied from stream service`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val instanceEventStreamService = mockk<InstanceEventStreamService>()
        val service = newService(config, instanceEventStreamService = instanceEventStreamService)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = emptySet(),
            namespaces = setOf("*"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        coEvery {
            instanceEventStreamService.watch(instanceId, principal, null, any())
        } throws GatewayPermissionDeniedException("Missing required scope 'lemline.watch'")

        val observer = TestObserver<com.lemline.gateway.v1.WatchInstanceEventsResponse>()
        withPrincipal(principal) {
            service.watchInstanceEvents(
                com.lemline.gateway.v1.WatchInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.PERMISSION_DENIED.code
        status.description shouldBe "Missing required scope 'lemline.watch'"
    }

    @Test
    fun `watchInstanceEvents rejects mismatched after cursor instance id`() {
        val config = testLemlineConfiguration(authenticationEnabled = true)
        val service = newService(config)
        val principal = GatewayPrincipal(
            subject = "watcher",
            scopes = setOf("lemline.watch"),
            namespaces = setOf("*"),
        )
        val instanceId = UUID.fromString("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")

        val observer = TestObserver<com.lemline.gateway.v1.WatchInstanceEventsResponse>()
        withPrincipal(principal) {
            service.watchInstanceEvents(
                com.lemline.gateway.v1.WatchInstanceEventsRequest.newBuilder()
                    .setInstanceId(instanceId.toString())
                    .setAfterCursor(
                        GatewayCursorCodec.encodeInstanceEventCursor(
                            InstanceEventCursor(
                                instanceId = "018f40c4-5f0f-7bf2-b0f7-4a4931227c32",
                                sequence = 5L,
                            )
                        )
                    )
                    .build(),
                observer,
            )
        }

        observer.await()
        val status = Status.fromThrowable(observer.error)
        status.code shouldBe Status.INVALID_ARGUMENT.code
        status.description shouldBe "after_cursor does not match instance_id"
    }

    private fun newService(
        config: com.lemline.runner.config.LemlineConfiguration,
        authorizer: GatewayAuthorizer = mockk(relaxed = true),
        startService: InstanceStartService = mockk(relaxed = true),
        instanceEventStreamService: InstanceEventStreamService = mockk(relaxed = true),
        definitionQueryService: DefinitionQueryService = mockk(relaxed = true),
        statsQueryService: StatsQueryService = mockk(relaxed = true),
        instanceQueryService: InstanceQueryService = mockk(relaxed = true),
        instanceEventQueryService: InstanceEventQueryService = mockk(relaxed = true),
        instanceStreamService: InstanceStreamService = mockk(relaxed = true),
    ) = WorkflowGatewayGrpcService(config).apply {
        this.authorizer = authorizer
        this.startService = startService
        this.instanceEventStreamService = instanceEventStreamService
        this.definitionQueryService = definitionQueryService
        this.statsQueryService = statsQueryService
        this.instanceQueryService = instanceQueryService
        this.instanceEventQueryService = instanceEventQueryService
        this.instanceStreamService = instanceStreamService
    }

    private fun ts(value: String): Timestamp =
        Timestamp.newBuilder().setSeconds(Instant.parse(value).epochSecond).build()

    private fun instanceSummaryRow(
        instanceId: UUID,
        namespace: String = "team-a",
        name: String = "orders",
        version: String = "1.0.0",
        status: InstanceStatus = InstanceStatus.PENDING,
        createdAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        startedAt: Instant? = null,
        completedAt: Instant? = null,
        faultedAt: Instant? = null,
        durationMs: Long? = null,
        error: ErrorSummaryRow? = null,
    ) = InstanceSummaryRow(
        instanceId = instanceId,
        namespace = namespace,
        name = name,
        version = version,
        status = status,
        createdAt = createdAt,
        startedAt = startedAt,
        completedAt = completedAt,
        faultedAt = faultedAt,
        durationMs = durationMs,
        error = error,
    )

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
