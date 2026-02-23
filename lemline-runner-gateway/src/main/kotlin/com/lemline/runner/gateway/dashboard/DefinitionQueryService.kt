// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.dashboard

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.definitions.DEFINITION_TABLE
import io.agroal.api.AgroalDataSource
import io.quarkus.agroal.DataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty

@ApplicationScoped
class DefinitionQueryService(
    @ConfigProperty(name = "lemline.analytics.postgresql.schema", defaultValue = "public")
    analyticsSchema: String,
    @ConfigProperty(name = "lemline.analytics.postgresql.table", defaultValue = "lemline_lifecycle_events")
    analyticsTable: String,
) {

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    @Inject
    @DataSource("analytics")
    lateinit var analyticsDataSource: Instance<AgroalDataSource>

    private val analyticsQualifiedTable = DashboardSqlSupport.qualifiedTable(analyticsSchema, analyticsTable)

    suspend fun listNamespaces(): List<String> {
        val primary = listPrimaryNamespaces()
        val analytics = listAnalyticsNamespaces()
        return (primary + analytics)
            .filter { it.isNotBlank() }
            .toSet()
            .sorted()
    }

    suspend fun listDefinitions(
        namespace: String,
        name: String?,
        latestOnly: Boolean,
    ): List<DefinitionSummaryRow> = withContext(Dispatchers.IO) {
        val trimmedNamespace = namespace.trim()
        require(trimmedNamespace.isNotBlank()) { "namespace must be provided" }

        val whereConditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        whereConditions += "namespace = ?"
        params += trimmedNamespace

        name?.trim()?.takeIf { it.isNotBlank() }?.let {
            whereConditions += "name = ?"
            params += it
        }

        val latestWhere = if (latestOnly) "WHERE rn = 1" else ""

        val sql = """
            SELECT
              namespace,
              name,
              version,
              created_at,
              updated_at,
              CASE WHEN rn = 1 THEN TRUE ELSE FALSE END AS is_latest,
              version_count
            FROM (
              SELECT
                namespace,
                name,
                version,
                created_at,
                updated_at,
                ROW_NUMBER() OVER (
                    PARTITION BY namespace, name
                    ORDER BY version DESC
                ) AS rn,
                COUNT(*) OVER (
                    PARTITION BY namespace, name
                ) AS version_count
              FROM $DEFINITION_TABLE
              WHERE ${whereConditions.joinToString(" AND ")}
            ) ranked
            $latestWhere
            ORDER BY name ASC, version DESC
        """.trimIndent()

        databaseConfig.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, value ->
                    stmt.setObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val createdAt = rs.getInstantOrNull("created_at") ?: Instant.EPOCH
                            add(
                                DefinitionSummaryRow(
                                    namespace = rs.getString("namespace"),
                                    name = rs.getString("name"),
                                    version = rs.getString("version"),
                                    isLatest = rs.getBoolean("is_latest"),
                                    versionCount = rs.getInt("version_count"),
                                    createdAt = createdAt,
                                    updatedAt = rs.getInstantOrNull("updated_at"),
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun getDefinition(
        namespace: String,
        name: String,
        version: String?,
    ): DefinitionDetailsRow? = withContext(Dispatchers.IO) {
        val trimmedNamespace = namespace.trim()
        val trimmedName = name.trim()

        require(trimmedNamespace.isNotBlank()) { "namespace must be provided" }
        require(trimmedName.isNotBlank()) { "name must be provided" }

        databaseConfig.withConnection { conn ->
            val selected = if (version.isNullOrBlank()) {
                val latestSql = """
                    SELECT namespace, name, version, definition
                    FROM $DEFINITION_TABLE
                    WHERE namespace = ? AND name = ?
                    ORDER BY version DESC
                    LIMIT 1
                """.trimIndent()

                conn.prepareStatement(latestSql).use { stmt ->
                    stmt.setString(1, trimmedNamespace)
                    stmt.setString(2, trimmedName)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            arrayOf(
                                rs.getString("namespace"),
                                rs.getString("name"),
                                rs.getString("version"),
                                rs.getString("definition")
                            )
                        }
                    }
                }
            } else {
                val exactSql = """
                    SELECT namespace, name, version, definition
                    FROM $DEFINITION_TABLE
                    WHERE namespace = ? AND name = ? AND version = ?
                    LIMIT 1
                """.trimIndent()

                conn.prepareStatement(exactSql).use { stmt ->
                    stmt.setString(1, trimmedNamespace)
                    stmt.setString(2, trimmedName)
                    stmt.setString(3, version.trim())
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            arrayOf(
                                rs.getString("namespace"),
                                rs.getString("name"),
                                rs.getString("version"),
                                rs.getString("definition")
                            )
                        }
                    }
                }
            } ?: return@withConnection null

            val versionsSql = """
                SELECT version
                FROM $DEFINITION_TABLE
                WHERE namespace = ? AND name = ?
                ORDER BY version DESC
            """.trimIndent()

            val versions = conn.prepareStatement(versionsSql).use { stmt ->
                stmt.setString(1, trimmedNamespace)
                stmt.setString(2, trimmedName)
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("version"))
                        }
                    }
                }
            }

            DefinitionDetailsRow(
                namespace = selected[0],
                name = selected[1],
                version = selected[2],
                definition = selected[3],
                availableVersions = versions,
            )
        }
    }

    private suspend fun listPrimaryNamespaces(): List<String> = withContext(Dispatchers.IO) {
        val sql = """
            SELECT DISTINCT namespace
            FROM $DEFINITION_TABLE
            WHERE namespace IS NOT NULL
            ORDER BY namespace
        """.trimIndent()

        databaseConfig.withConnection { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("namespace"))
                        }
                    }
                }
            }
        }
    }

    private suspend fun listAnalyticsNamespaces(): List<String> = withContext(Dispatchers.IO) {
        if (!analyticsDataSource.isResolvable) return@withContext emptyList()

        val sql = """
            SELECT DISTINCT lemline_workflow_namespace
            FROM $analyticsQualifiedTable
            WHERE lemline_workflow_namespace IS NOT NULL
            ORDER BY lemline_workflow_namespace
        """.trimIndent()

        analyticsDataSource.get().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString("lemline_workflow_namespace"))
                        }
                    }
                }
            }
        }
    }
}
