// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.definitions

import java.time.Instant

data class DefinitionListFilter(
    val namespace: String? = null,
    val name: String? = null,
    val latestOnly: Boolean = true,
    val allowedNamespaces: Set<String>? = null,
    val pageSize: Int = 50,
)

data class DefinitionSummaryRow(
    val namespace: String,
    val name: String,
    val version: String,
    val isLatest: Boolean,
    val versionCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class DefinitionListResult(
    val definitions: List<DefinitionSummaryRow>,
    val nextPage: DefinitionPageBoundary?,
    val totalCount: Long,
)

data class DefinitionDetailsRow(
    val namespace: String,
    val name: String,
    val version: String,
    val source: String,
    val availableVersions: List<String>,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

data class DefinitionPageBoundary(
    val namespace: String,
    val name: String,
    val version: String,
)
