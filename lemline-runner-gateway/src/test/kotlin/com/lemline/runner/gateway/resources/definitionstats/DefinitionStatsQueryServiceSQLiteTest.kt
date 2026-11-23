// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitionstats

import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.ANALYTICS_TYPE_SQLITE
import java.nio.file.Path
import javax.sql.DataSource
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteDataSource

@DisplayName("DefinitionStatsQueryService [SQLite]")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DefinitionStatsQueryServiceSQLiteTest : DefinitionStatsQueryServiceTestBase() {
    private val testDb = SQLiteDataSource()

    override val analyticsType = ANALYTICS_TYPE_SQLITE
    override val qualifiedTable = "\"$ANALYTICS_LIFECYCLE_EVENTS_TABLE\""
    override fun dataSource(): DataSource = testDb

    @BeforeAll
    fun createTable(@TempDir directory: Path) {
        testDb.url = "jdbc:sqlite:${directory.resolve("analytics.db")}"
        dataSource().connection.use { conn ->
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE $qualifiedTable (
                        id INTEGER PRIMARY KEY,
                        type TEXT NOT NULL,
                        event_time INTEGER,
                        lemline_workflow_id TEXT,
                        lemline_workflow_namespace TEXT,
                        lemline_workflow_name TEXT,
                        lemline_workflow_version TEXT
                    ) STRICT
                    """.trimIndent()
                )
            }
        }
    }
}
