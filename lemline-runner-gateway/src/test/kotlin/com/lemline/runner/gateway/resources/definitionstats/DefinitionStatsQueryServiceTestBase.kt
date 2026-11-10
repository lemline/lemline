// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.runner.gateway.support.testLemlineConfiguration
import io.agroal.api.AgroalDataSource
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the definition stats SQL against a real analytics database.
 * Subclasses provide the dialect: the analytics store supports H2 and PostgreSQL.
 */
abstract class DefinitionStatsQueryServiceTestBase {

    protected abstract val analyticsType: String
    protected abstract fun dataSource(): DataSource

    /** Table name as the service qualifies it for this dialect. */
    protected abstract val qualifiedTable: String

    private val service by lazy {
        val agroal = mockk<AgroalDataSource> {
            every { connection } answers { dataSource().connection }
        }
        DefinitionStatsQueryService(testLemlineConfiguration(analyticsType = analyticsType)).apply {
            analyticsDataSourceProvider = mockk<AnalyticsDataSourceProvider> {
                every { require() } returns agroal
            }
        }
    }

    private val timeFrom = Instant.parse("2026-01-01T00:00:00Z")
    private val timeTo = Instant.parse("2026-01-02T00:00:00Z")

    // Three instances of the same definition (team-a/orders/1.0.0).
    private val first = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val second = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val third = UUID.fromString("00000000-0000-0000-0000-00000000000c")

    @BeforeEach
    fun clearEvents() {
        dataSource().connection.use { conn ->
            conn.createStatement().use { it.executeUpdate("DELETE FROM $qualifiedTable") }
        }
    }

    @Test
    fun `bounded window ignores activity at or after time_to`() = runTest {
        // Started inside the window, completed after it: counted as running at time_to.
        insertEvent(first, LifecycleEventType.WORKFLOW_STARTED, "2026-01-01T10:00:00Z")
        insertEvent(first, LifecycleEventType.WORKFLOW_COMPLETED, "2026-01-02T10:00:00Z")
        // Started after the window: not counted at all.
        insertEvent(second, LifecycleEventType.WORKFLOW_STARTED, "2026-01-02T11:00:00Z")

        val result = service.listDefinitionStats(filter(timeTo = timeTo), snapshotSequence = maxSequence())

        result.stats.shouldContainExactly(statsRow(started = 1, completed = 0, running = 1))
        result.totalCount shouldBe 1
    }

    @Test
    fun `open window counts activity up to the snapshot sequence`() = runTest {
        insertEvent(first, LifecycleEventType.WORKFLOW_STARTED, "2026-01-01T10:00:00Z")
        insertEvent(first, LifecycleEventType.WORKFLOW_COMPLETED, "2026-01-02T10:00:00Z")
        insertEvent(second, LifecycleEventType.WORKFLOW_STARTED, "2026-01-02T11:00:00Z")
        val snapshot = maxSequence()
        // Ingested after the snapshot: belongs to the next one.
        insertEvent(third, LifecycleEventType.WORKFLOW_STARTED, "2026-01-02T12:00:00Z")

        val result = service.listDefinitionStats(filter(timeTo = null), snapshotSequence = snapshot)

        result.stats.shouldContainExactly(statsRow(started = 2, completed = 1, running = 1))
        result.totalCount shouldBe 1
        service.queryDefinitionStatsSnapshot(filter(timeTo = null), maxSequence())
            .shouldContainExactly(statsRow(started = 3, completed = 1, running = 2))
    }

    private fun filter(timeTo: Instant?) = DefinitionStatsFilter(timeFrom = timeFrom, timeTo = timeTo)

    private fun statsRow(started: Long, completed: Long, running: Long) = DefinitionStatsRow(
        namespace = "team-a",
        name = "orders",
        version = "1.0.0",
        startedCount = started,
        completedCount = completed,
        faultedCount = 0,
        runningCountAtTimeTo = running,
    )

    private fun insertEvent(workflowId: UUID, type: LifecycleEventType, eventTime: String) {
        dataSource().connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO $qualifiedTable
                  (type, event_time, lemline_workflow_id, lemline_workflow_namespace,
                   lemline_workflow_name, lemline_workflow_version)
                VALUES (?, ?, ?, 'team-a', 'orders', '1.0.0')
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, type.type)
                stmt.setTimestamp(2, Timestamp.from(Instant.parse(eventTime)))
                stmt.setObject(3, workflowId)
                stmt.executeUpdate()
            }
        }
    }

    private fun maxSequence(): Long = dataSource().connection.use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT MAX(id) FROM $qualifiedTable").use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }
    }
}
