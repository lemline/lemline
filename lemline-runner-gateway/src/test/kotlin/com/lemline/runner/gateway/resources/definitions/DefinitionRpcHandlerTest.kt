// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitions

import com.lemline.gateway.v1.GetDefinitionRequest
import com.lemline.gateway.v1.ListDefinitionsRequest
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.grpc.DefinitionPageCursor
import com.lemline.runner.gateway.grpc.GatewayCursorCodec
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DefinitionRpcHandler")
class DefinitionRpcHandlerTest {

    private val authorizer = mockk<GatewayAuthorizer>()
    private val definitionQueryService = mockk<DefinitionQueryService>()

    private val principal = GatewayPrincipal(
        subject = "reader",
        scopes = emptySet(),
        namespaces = setOf("team-b", "team-a"),
    )

    private val service = DefinitionRpcHandler(
        config = testLemlineConfiguration(authenticationEnabled = true),
    ).apply {
        this.authorizer = this@DefinitionRpcHandlerTest.authorizer
        this.definitionQueryService = this@DefinitionRpcHandlerTest.definitionQueryService
    }

    @Test
    fun `listDefinitions defaults latestOnly and restricts to accessible namespaces`() = runTest {
        var capturedFilter: DefinitionListFilter? = null

        coEvery { definitionQueryService.listDefinitions(any(), any(), any()) } coAnswers {
            capturedFilter = firstArg()
            DefinitionListResult(
                definitions = listOf(
                    summaryRow(
                        namespace = "team-a",
                        name = "orders",
                        version = "2.0.0",
                        isLatest = true,
                        versionCount = 2,
                    )
                ),
                nextPage = null,
                totalCount = 1L,
            )
        }

        val response = service.listDefinitions(
            ListDefinitionsRequest.newBuilder()
                .setName("orders")
                .build(),
            principal,
        )

        capturedFilter shouldBe DefinitionListFilter(
            namespace = null,
            name = "orders",
            latestOnly = true,
            allowedNamespaces = setOf("team-a", "team-b"),
            pageSize = 0,
        )
        response.totalCount shouldBe 1L
        response.definitionsList.single().apply {
            namespace shouldBe "team-a"
            name shouldBe "orders"
            version shouldBe "2.0.0"
            isLatest shouldBe true
            versionCount shouldBe 2
        }
    }

    @Test
    fun `listDefinitions encodes next page cursor from result boundary`() = runTest {
        every { authorizer.requireNamespace(principal, "team-a") } just runs

        var capturedSnapshotAt: Instant? = null

        coEvery { definitionQueryService.listDefinitions(any(), any(), any()) } coAnswers {
            capturedSnapshotAt = secondArg()
            DefinitionListResult(
                definitions = listOf(
                    summaryRow(
                        namespace = "team-a",
                        name = "orders",
                        version = "2.0.0",
                        isLatest = false,
                        versionCount = 3,
                    )
                ),
                nextPage = DefinitionPageBoundary(
                    namespace = "team-a",
                    name = "orders",
                    version = "2.0.0",
                ),
                totalCount = 3L,
            )
        }

        val response = service.listDefinitions(
            ListDefinitionsRequest.newBuilder()
                .setNamespace("team-a")
                .setLatestOnly(false)
                .build(),
            principal,
        )

        val cursor = GatewayCursorCodec.decodeDefinitionPageCursor(response.nextPageCursor)
        cursor shouldBe DefinitionPageCursor(
            namespace = "team-a",
            name = null,
            latestOnly = false,
            allowedNamespaces = listOf("team-a"),
            snapshotAt = capturedSnapshotAt.toString(),
            lastNamespace = "team-a",
            lastName = "orders",
            lastVersion = "2.0.0",
        )
    }

    @Test
    fun `listDefinitions rejects mismatched page cursor`() = runTest {
        every { authorizer.requireNamespace(principal, "team-a") } just runs

        val pageCursor = GatewayCursorCodec.encodeDefinitionPageCursor(
            DefinitionPageCursor(
                namespace = "team-b",
                name = null,
                latestOnly = true,
                allowedNamespaces = listOf("team-b"),
                snapshotAt = "2026-03-08T00:00:00Z",
                lastNamespace = "team-b",
                lastName = "orders",
                lastVersion = "1.0.0",
            )
        )

        val error = shouldThrow<IllegalArgumentException> {
            service.listDefinitions(
                ListDefinitionsRequest.newBuilder()
                    .setNamespace("team-a")
                    .setPageCursor(pageCursor)
                    .build(),
                principal,
            )
        }

        error.message shouldBe "page_cursor does not match namespace filter"
        coVerify(exactly = 0) { definitionQueryService.listDefinitions(any(), any(), any()) }
    }

    @Test
    fun `getDefinition trims inputs authorizes namespace and maps response`() = runTest {
        every { authorizer.requireNamespace(principal, "team-a") } just runs
        coEvery { definitionQueryService.getDefinition("team-a", "orders", null) } returns DefinitionDetailsRow(
            namespace = "team-a",
            name = "orders",
            version = "2.0.0",
            source = "document:\n  namespace: team-a\n",
            availableVersions = listOf("2.0.0", "1.0.0"),
            createdAt = Instant.parse("2026-01-02T10:15:30Z"),
            updatedAt = Instant.parse("2026-01-02T11:15:30Z"),
        )

        val response = service.getDefinition(
            GetDefinitionRequest.newBuilder()
                .setNamespace(" team-a ")
                .setName(" orders ")
                .build(),
            principal,
        )

        with(response.definition) {
            namespace shouldBe "team-a"
            name shouldBe "orders"
            version shouldBe "2.0.0"
            source shouldBe "document:\n  namespace: team-a\n"
            availableVersionsList.shouldContainExactly("2.0.0", "1.0.0")
        }
        coVerify(exactly = 1) { definitionQueryService.getDefinition("team-a", "orders", null) }
    }

    private fun summaryRow(
        namespace: String,
        name: String,
        version: String,
        isLatest: Boolean,
        versionCount: Int,
    ) = DefinitionSummaryRow(
        namespace = namespace,
        name = name,
        version = version,
        isLatest = isLatest,
        versionCount = versionCount,
        createdAt = Instant.parse("2026-01-02T10:15:30Z"),
        updatedAt = Instant.parse("2026-01-02T11:15:30Z"),
    )
}
