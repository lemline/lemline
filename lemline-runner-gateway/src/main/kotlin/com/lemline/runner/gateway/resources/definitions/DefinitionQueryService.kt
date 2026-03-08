// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.definitions

import com.lemline.runner.common.config.DatabaseConfig
import com.lemline.runner.definitions.DEFINITION_TABLE
import com.lemline.runner.gateway.persistence.SqlQuery
import com.lemline.runner.gateway.persistence.getInstantOrNull
import com.lemline.runner.gateway.persistence.normalizePageSize
import com.lemline.runner.gateway.persistence.placeholders
import com.lemline.runner.gateway.persistence.setGatewayObject
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@ApplicationScoped
class DefinitionQueryService {

    @Inject
    lateinit var databaseConfig: DatabaseConfig

    suspend fun listDefinitions(
        filter: DefinitionListFilter,
        snapshotAt: Instant,
        page: DefinitionPageBoundary? = null,
    ): DefinitionListResult = withContext(Dispatchers.IO) {
        if (filter.allowedNamespaces?.isEmpty() == true) {
            return@withContext DefinitionListResult(emptyList(), null, 0L)
        }

        val normalizedPageSize = normalizePageSize(filter.pageSize)
        val query = buildListDefinitionsQuery(filter, snapshotAt, page, normalizedPageSize + 1)

        val rows = databaseConfig.withConnection { conn ->
            conn.prepareStatement(query.sql).use { stmt ->
                query.params.forEachIndexed { index, value ->
                    stmt.setGatewayObject(index + 1, value)
                }
                stmt.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                DefinitionSummaryRow(
                                    namespace = rs.getString("namespace"),
                                    name = rs.getString("name"),
                                    version = rs.getString("version"),
                                    isLatest = rs.getBoolean("is_latest"),
                                    versionCount = rs.getInt("version_count"),
                                    createdAt = rs.getInstantOrNull("created_at") ?: Instant.EPOCH,
                                    updatedAt = rs.getInstantOrNull("updated_at"),
                                )
                            )
                        }
                    }
                }
            }
        }

        val hasMore = rows.size > normalizedPageSize
        val pageRows = if (hasMore) rows.take(normalizedPageSize) else rows
        val nextPage = pageRows.lastOrNull()
            ?.takeIf { hasMore }
            ?.let { DefinitionPageBoundary(it.namespace, it.name, it.version) }

        DefinitionListResult(
            definitions = pageRows,
            nextPage = nextPage,
            totalCount = countDefinitions(filter, snapshotAt),
        )
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
                val sql = """
                    SELECT namespace, name, version, definition, created_at, updated_at
                    FROM $DEFINITION_TABLE
                    WHERE namespace = ? AND name = ?
                    ORDER BY version DESC
                    LIMIT 1
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, trimmedNamespace)
                    stmt.setString(2, trimmedName)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            SelectedDefinition(
                                namespace = rs.getString("namespace"),
                                name = rs.getString("name"),
                                version = rs.getString("version"),
                                source = rs.getString("definition"),
                                createdAt = rs.getInstantOrNull("created_at") ?: Instant.EPOCH,
                                updatedAt = rs.getInstantOrNull("updated_at"),
                            )
                        }
                    }
                }
            } else {
                val sql = """
                    SELECT namespace, name, version, definition, created_at, updated_at
                    FROM $DEFINITION_TABLE
                    WHERE namespace = ? AND name = ? AND version = ?
                    LIMIT 1
                """.trimIndent()

                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, trimmedNamespace)
                    stmt.setString(2, trimmedName)
                    stmt.setString(3, version.trim())
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) {
                            null
                        } else {
                            SelectedDefinition(
                                namespace = rs.getString("namespace"),
                                name = rs.getString("name"),
                                version = rs.getString("version"),
                                source = rs.getString("definition"),
                                createdAt = rs.getInstantOrNull("created_at") ?: Instant.EPOCH,
                                updatedAt = rs.getInstantOrNull("updated_at"),
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
                namespace = selected.namespace,
                name = selected.name,
                version = selected.version,
                source = selected.source,
                availableVersions = versions,
                createdAt = selected.createdAt,
                updatedAt = selected.updatedAt,
            )
        }
    }

    private suspend fun countDefinitions(filter: DefinitionListFilter, snapshotAt: Instant): Long =
        withContext(Dispatchers.IO) {
            val query = buildCountDefinitionsQuery(filter, snapshotAt)
            databaseConfig.withConnection { conn ->
                conn.prepareStatement(query.sql).use { stmt ->
                    query.params.forEachIndexed { index, value ->
                        stmt.setGatewayObject(index + 1, value)
                    }
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getLong("total_count") else 0L
                    }
                }
            }
        }

    private fun buildListDefinitionsQuery(
        filter: DefinitionListFilter,
        snapshotAt: Instant,
        page: DefinitionPageBoundary?,
        limit: Int,
    ): SqlQuery {
        val queryParts = buildDefinitionQueryParts(filter, snapshotAt)
        val outerConditions = mutableListOf<String>()
        if (filter.latestOnly) {
            outerConditions += "rn = 1"
        }
        val pagingWhere = if (page == null) {
            ""
        } else {
            """
            AND (
              namespace > ?
              OR (namespace = ? AND name > ?)
              OR (namespace = ? AND name = ? AND version < ?)
            )
            """.trimIndent()
        }
        val pagingParams = if (page == null) {
            emptyList()
        } else {
            listOf(page.namespace, page.namespace, page.name, page.namespace, page.name, page.version)
        }
        if (outerConditions.isEmpty()) {
            outerConditions += "1 = 1"
        }

        val sql = """
            ${queryParts.sql}
            SELECT namespace, name, version, created_at, updated_at, is_latest, version_count
            FROM ranked
            WHERE ${outerConditions.joinToString(" AND ")}
            $pagingWhere
            ORDER BY namespace ASC, name ASC, version DESC
            LIMIT ?
        """.trimIndent()

        return SqlQuery(sql, queryParts.params + pagingParams + limit)
    }

    private fun buildCountDefinitionsQuery(filter: DefinitionListFilter, snapshotAt: Instant): SqlQuery {
        val queryParts = buildDefinitionQueryParts(filter, snapshotAt)
        val sql = if (filter.latestOnly) {
            """
            ${queryParts.sql}
            SELECT COUNT(*) AS total_count
            FROM ranked
            WHERE rn = 1
            """.trimIndent()
        } else {
            """
            ${queryParts.sql}
            SELECT COUNT(*) AS total_count
            FROM ranked
            """.trimIndent()
        }
        return SqlQuery(sql, queryParts.params)
    }

    private fun buildDefinitionQueryParts(filter: DefinitionListFilter, snapshotAt: Instant): SqlQuery {
        val whereConditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        whereConditions += "COALESCE(updated_at, created_at) <= ?"
        params += snapshotAt

        filter.namespace?.trim()?.takeIf { it.isNotBlank() }?.let {
            whereConditions += "namespace = ?"
            params += it
        }

        filter.name?.trim()?.takeIf { it.isNotBlank() }?.let {
            whereConditions += "name = ?"
            params += it
        }

        filter.allowedNamespaces?.takeIf { it.isNotEmpty() }?.let { namespaces ->
            whereConditions += "namespace IN (${placeholders(namespaces.size)})"
            params.addAll(namespaces.sorted())
        }

        val cteSql = """
            WITH ranked AS (
              SELECT
                namespace,
                name,
                version,
                created_at,
                updated_at,
                CASE WHEN ROW_NUMBER() OVER (
                    PARTITION BY namespace, name
                    ORDER BY version DESC
                ) = 1 THEN TRUE ELSE FALSE END AS is_latest,
                ROW_NUMBER() OVER (
                    PARTITION BY namespace, name
                    ORDER BY version DESC
                ) AS rn,
                COUNT(*) OVER (
                    PARTITION BY namespace, name
                ) AS version_count
              FROM $DEFINITION_TABLE
              WHERE ${whereConditions.joinToString(" AND ")}
            )
        """.trimIndent()

        return SqlQuery(cteSql, params)
    }

    private data class SelectedDefinition(
        val namespace: String,
        val name: String,
        val version: String,
        val source: String,
        val createdAt: Instant,
        val updatedAt: Instant?,
    )
}
