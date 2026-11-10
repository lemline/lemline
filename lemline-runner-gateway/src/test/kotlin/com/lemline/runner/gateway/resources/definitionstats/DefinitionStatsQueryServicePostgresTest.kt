// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.ANALYTICS_TYPE_POSTGRESQL
import com.lemline.runner.common.test.PostgresTestDatabaseConfig
import com.lemline.runner.common.test.RequiresDocker
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance

@RequiresDocker
@DisplayName("DefinitionStatsQueryService [PostgreSQL]")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefinitionStatsQueryServicePostgresTest : DefinitionStatsQueryServiceTestBase() {

    private val testDb = PostgresTestDatabaseConfig()

    override val analyticsType = ANALYTICS_TYPE_POSTGRESQL
    override val qualifiedTable = "\"public\".\"$ANALYTICS_LIFECYCLE_EVENTS_TABLE\""
    override fun dataSource(): DataSource = testDb.jdbcDataSource

    @BeforeAll
    fun createTable() {
        testDb.start()
        // Columns read by the stats query, as in the analytics PostgreSQL migration.
        dataSource().connection.use { conn ->
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE $qualifiedTable (
                        id BIGSERIAL PRIMARY KEY,
                        type VARCHAR(255) NOT NULL,
                        event_time TIMESTAMPTZ NULL,
                        lemline_workflow_id UUID NULL,
                        lemline_workflow_namespace VARCHAR(255) NULL,
                        lemline_workflow_name VARCHAR(255) NULL,
                        lemline_workflow_version VARCHAR(255) NULL
                    )
                    """.trimIndent()
                )
            }
        }
    }

    @AfterAll
    fun closeDatabase() {
        testDb.close()
    }
}
