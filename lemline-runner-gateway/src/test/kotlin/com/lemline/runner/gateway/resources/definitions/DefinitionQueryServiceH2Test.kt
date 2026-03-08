// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitions

import com.lemline.runner.common.test.H2TestDatabaseConfig
import com.lemline.runner.definitions.DEFINITION_TABLE
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.sql.Types
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@DisplayName("DefinitionQueryService [H2]")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefinitionQueryServiceH2Test {

    private val testDb = H2TestDatabaseConfig()

    private val service = DefinitionQueryService().apply {
        databaseConfig = testDb
    }

    @BeforeAll
    fun createSchema() {
        runBlocking {
            testDb.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS $DEFINITION_TABLE
                        (
                            namespace  VARCHAR(255)             NOT NULL,
                            name       VARCHAR(255)             NOT NULL,
                            version    VARCHAR(255)             NOT NULL,
                            definition CLOB                     NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                            updated_at TIMESTAMP WITH TIME ZONE,
                            PRIMARY KEY (namespace, name, version)
                        )
                        """.trimIndent()
                    )
                }
            }
        }
    }

    @AfterAll
    fun closeDatabase() {
        testDb.close()
    }

    @BeforeEach
    fun clearDefinitions() {
        runBlocking {
            testDb.withConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM $DEFINITION_TABLE")
                }
            }
        }
    }

    @Test
    fun `listDefinitions returns latest rows with counts`() = runTest {
        insertDefinition("team-a", "orders", "1.0.0", "orders-v1", createdAt = "2026-01-01T00:00:00Z")
        insertDefinition("team-a", "orders", "2.0.0", "orders-v2", createdAt = "2026-01-02T00:00:00Z")
        insertDefinition("team-b", "payments", "1.0.0", "payments-v1", createdAt = "2026-01-03T00:00:00Z")

        val result = service.listDefinitions(
            filter = DefinitionListFilter(latestOnly = true, pageSize = 50),
            snapshotAt = Instant.parse("2026-01-04T00:00:00Z"),
        )

        result.definitions.map { Triple(it.namespace, it.name, it.version) }
            .shouldContainExactly(
                Triple("team-a", "orders", "2.0.0"),
                Triple("team-b", "payments", "1.0.0"),
            )
        result.definitions.first().versionCount shouldBe 2
        result.totalCount shouldBe 2L
        result.nextPage shouldBe null
    }

    @Test
    fun `listDefinitions honors snapshot and page boundary`() = runTest {
        insertDefinition("team-a", "orders", "1.0.0", "orders-v1", createdAt = "2026-01-01T00:00:00Z")
        insertDefinition("team-a", "orders", "2.0.0", "orders-v2", createdAt = "2026-01-03T00:00:00Z")
        insertDefinition("team-a", "refunds", "1.0.0", "refunds-v1", createdAt = "2026-01-02T00:00:00Z")

        val filter = DefinitionListFilter(
            namespace = "team-a",
            latestOnly = true,
            pageSize = 1,
        )
        val snapshotAt = Instant.parse("2026-01-02T12:00:00Z")

        val firstPage = service.listDefinitions(filter, snapshotAt)
        firstPage.definitions.map { it.name to it.version }
            .shouldContainExactly("orders" to "1.0.0")
        firstPage.nextPage shouldBe DefinitionPageBoundary(
            namespace = "team-a",
            name = "orders",
            version = "1.0.0",
        )
        firstPage.totalCount shouldBe 2L

        val secondPage = service.listDefinitions(filter, snapshotAt, firstPage.nextPage)
        secondPage.definitions.map { it.name to it.version }
            .shouldContainExactly("refunds" to "1.0.0")
        secondPage.totalCount shouldBe 2L
        secondPage.nextPage shouldBe null
    }

    @Test
    fun `getDefinition returns latest version and full version history`() = runTest {
        insertDefinition("team-a", "orders", "1.0.0", "orders-v1", createdAt = "2026-01-01T00:00:00Z")
        insertDefinition("team-a", "orders", "2.0.0", "orders-v2", createdAt = "2026-01-02T00:00:00Z")
        insertDefinition("team-a", "orders", "3.0.0", "orders-v3", createdAt = "2026-01-03T00:00:00Z")

        val result = service.getDefinition(" team-a ", " orders ", null)

        result shouldBe DefinitionDetailsRow(
            namespace = "team-a",
            name = "orders",
            version = "3.0.0",
            source = "orders-v3",
            availableVersions = listOf("3.0.0", "2.0.0", "1.0.0"),
            createdAt = Instant.parse("2026-01-03T00:00:00Z"),
            updatedAt = null,
        )
    }

    private suspend fun insertDefinition(
        namespace: String,
        name: String,
        version: String,
        source: String,
        createdAt: String,
        updatedAt: String? = null,
    ) {
        testDb.withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO $DEFINITION_TABLE (namespace, name, version, definition, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, namespace)
                stmt.setString(2, name)
                stmt.setString(3, version)
                stmt.setString(4, source)
                stmt.setObject(5, Instant.parse(createdAt))
                if (updatedAt == null) {
                    stmt.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
                } else {
                    stmt.setObject(6, Instant.parse(updatedAt))
                }
                stmt.executeUpdate()
            }
        }
    }
}
