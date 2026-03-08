// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.start

import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowId
import com.lemline.common.values.WorkflowInfo
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import com.lemline.core.lifecycleevents.LifecycleEventHook
import com.lemline.core.orchestrator.StepByStepOrchestrator
import com.lemline.core.workflows.WorkflowCache
import com.lemline.gateway.v1.StartInstanceRequest
import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.definitions.Definitions
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayConflictException
import com.lemline.runner.gateway.outbox.GatewayCommandModel
import com.lemline.runner.gateway.outbox.GatewayCommandRepository
import com.lemline.runner.schedules.ScheduleModel
import com.lemline.runner.schedules.ScheduleRepository
import com.lemline.runner.starters.Starter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import java.sql.Connection
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InstanceStartService")
class InstanceStartServiceTest {

    private val definitions = mockk<Definitions>()
    private val starter = Starter().apply {
        definitions = this@InstanceStartServiceTest.definitions
    }
    private val scheduleRepository = mockk<ScheduleRepository>()
    private val gatewayOutboxRepository = mockk<GatewayCommandRepository>()
    private val lifecycleHook = mockk<LifecycleEventHook>(relaxed = true)
    private val authorizer = mockk<GatewayAuthorizer>()

    private val databaseConfig = object : DatabaseConfig {
        override val dbType: DatabaseType = DatabaseType.H2

        override suspend fun <R> withConnection(connection: Connection?, block: suspend (Connection) -> R): R =
            block(connection ?: mockk(relaxed = true))

        override suspend fun <R> withTransaction(connection: Connection?, block: suspend (Connection) -> R): R =
            block(connection ?: mockk(relaxed = true))
    }

    private val service = InstanceStartService().apply {
        this.starter = this@InstanceStartServiceTest.starter
        this.databaseConfig = this@InstanceStartServiceTest.databaseConfig
        this.scheduleRepository = this@InstanceStartServiceTest.scheduleRepository
        this.gatewayOutboxRepository = this@InstanceStartServiceTest.gatewayOutboxRepository
        this.lifecycleHook = this@InstanceStartServiceTest.lifecycleHook
        this.authorizer = this@InstanceStartServiceTest.authorizer
    }

    private val principal = GatewayPrincipal(
        subject = "starter",
        scopes = setOf("lemline.start"),
        namespaces = setOf("*"),
    )

    @BeforeEach
    fun setUp() {
        WorkflowCache.clear()
        every { authorizer.requireScope(principal, "lemline.start") } just runs
        every { authorizer.requireNamespace(principal, any()) } just runs
        coEvery { scheduleRepository.insert(any<ScheduleModel>(), any<Connection>()) } returns 1
    }

    @Test
    fun `generates instance id when request omits one`() = runTest {
        val inserted = slot<GatewayCommandModel>()
        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("1.0.0")
        coEvery { gatewayOutboxRepository.insert(capture(inserted), any<Connection>()) } returns 1

        val result = service.start(
            StartInstanceRequest.newBuilder()
                .setNamespace("team-a")
                .setName("orders")
                .build(),
            principal,
        )

        result.instanceId shouldBe inserted.captured.workflowId
        result.result shouldBe StartInstanceOutcome.ACCEPTED_NEW
        result.version shouldBe WorkflowVersion("1.0.0")
        coVerify(exactly = 1) { lifecycleHook.onWorkflowCreated(any(), any()) }
    }

    @Test
    fun `omitted version lets starter resolve latest version`() = runTest {
        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("2.3.4")
        coEvery { gatewayOutboxRepository.insert(any<GatewayCommandModel>(), any<Connection>()) } returns 1

        val result = service.start(
            StartInstanceRequest.newBuilder()
                .setNamespace("team-a")
                .setName("orders")
                .build(),
            principal,
        )

        result.version shouldBe WorkflowVersion("2.3.4")
        coVerify(exactly = 1) { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) }
    }

    @Test
    fun `missing input defaults to empty object`() = runTest {
        val inserted = slot<GatewayCommandModel>()
        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("1.0.0")
        coEvery { gatewayOutboxRepository.insert(capture(inserted), any<Connection>()) } returns 1

        service.start(
            StartInstanceRequest.newBuilder()
                .setNamespace("team-a")
                .setName("orders")
                .build(),
            principal,
        )

        inserted.captured.workflowState.nodeStack.rootState.workflowInput shouldBe buildJsonObject {}
    }

    @Test
    fun `protobuf value input is converted to json`() = runTest {
        val inserted = slot<GatewayCommandModel>()
        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("1.0.0")
        coEvery { gatewayOutboxRepository.insert(capture(inserted), any<Connection>()) } returns 1

        service.start(
            StartInstanceRequest.newBuilder()
                .setNamespace("team-a")
                .setName("orders")
                .setInput(
                    Value.newBuilder()
                        .setStructValue(
                            Struct.newBuilder()
                                .putFields("count", Value.newBuilder().setNumberValue(3.0).build())
                                .putFields("enabled", Value.newBuilder().setBoolValue(true).build())
                                .putFields("note", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                                .build()
                        )
                        .build()
                )
                .build(),
            principal,
        )

        inserted.captured.workflowState.nodeStack.rootState.workflowInput shouldBe buildJsonObject {
            put("count", 3.0)
            put("enabled", true)
            put("note", JsonNull)
        }
    }

    @Test
    fun `duplicate request with same resolved version is accepted idempotently`() = runTest {
        val request = StartInstanceRequest.newBuilder()
            .setNamespace("team-a")
            .setName("orders")
            .setInstanceId("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
            .build()
        val workflowId = WorkflowId(IDV7.from(request.instanceId))
        val workflowInput = buildJsonObject {}

        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("9.9.9")
        coEvery { gatewayOutboxRepository.insert(any<GatewayCommandModel>(), any<Connection>()) } returns 0
        coEvery { gatewayOutboxRepository.findByWorkflowId(workflowId) } returns existingOutbox(
            workflowId = workflowId,
            workflowInput = workflowInput,
            version = WorkflowVersion("9.9.9"),
        )

        val result = service.start(request, principal)

        result.result shouldBe StartInstanceOutcome.ACCEPTED_EXISTING
        coVerify(exactly = 0) { lifecycleHook.onWorkflowCreated(any(), any()) }
    }

    @Test
    fun `duplicate request with conflicting resolved version is rejected`() = runTest {
        val request = StartInstanceRequest.newBuilder()
            .setNamespace("team-a")
            .setName("orders")
            .setInstanceId("018f40c4-5f0f-7bf2-b0f7-4a4931227c31")
            .build()
        val workflowId = WorkflowId(IDV7.from(request.instanceId))
        val workflowInput = buildJsonObject {}

        coEvery { definitions.get(WorkflowNamespace("team-a"), WorkflowName("orders"), null) } returns workflow("2.0.0")
        coEvery { gatewayOutboxRepository.insert(any<GatewayCommandModel>(), any<Connection>()) } returns 0
        coEvery { gatewayOutboxRepository.findByWorkflowId(workflowId) } returns existingOutbox(
            workflowId = workflowId,
            workflowInput = workflowInput,
            version = WorkflowVersion("1.0.0"),
        )

        shouldThrow<GatewayConflictException> {
            service.start(request, principal)
        }
    }

    private fun existingOutbox(
        workflowId: WorkflowId,
        workflowInput: kotlinx.serialization.json.JsonElement,
        version: WorkflowVersion,
    ): GatewayCommandModel =
        GatewayCommandModel(
            id = IDV7.random(),
            instanceMessage = InstanceMessage(
                workflowInfo = WorkflowInfo(
                    namespace = WorkflowNamespace("team-a"),
                    name = WorkflowName("orders"),
                    version = version,
                ),
                workflowState = StepByStepOrchestrator.initCmd(workflowId, workflowInput, false),
            ),
            outboxScheduledFor = null,
        )

    private fun workflow(version: String): io.serverlessworkflow.api.types.Workflow =
        WorkflowCache.parseYaml(
            """
            document:
              dsl: '1.0.0'
              namespace: team-a
              name: orders
              version: '$version'
            do:
              - log:
                  call: log
                  with:
                    message: "Hello"
            """.trimIndent()
        )
}
