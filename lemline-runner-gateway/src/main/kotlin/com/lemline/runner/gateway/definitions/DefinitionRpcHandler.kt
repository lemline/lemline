// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.definitions

import com.lemline.gateway.v1.DefinitionSummary
import com.lemline.gateway.v1.GetDefinitionRequest
import com.lemline.gateway.v1.GetDefinitionResponse
import com.lemline.gateway.v1.ListDefinitionsRequest
import com.lemline.gateway.v1.ListDefinitionsResponse
import com.lemline.gateway.v1.WorkflowDefinition
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import com.lemline.runner.gateway.errors.GatewayNotFoundException
import com.lemline.runner.gateway.grpc.DefinitionPageCursor
import com.lemline.runner.gateway.grpc.GatewayCursorCodec
import com.lemline.runner.gateway.grpc.allowedNamespacesForRead
import com.lemline.runner.gateway.grpc.normalizeAllowedNamespaces
import com.lemline.runner.gateway.grpc.optionalString
import com.lemline.runner.gateway.grpc.parseCursorInstant
import com.lemline.runner.gateway.grpc.requiredString
import com.lemline.runner.gateway.grpc.toBoundary
import com.lemline.runner.gateway.grpc.toProtoTimestamp
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Instant

@ApplicationScoped
class DefinitionRpcHandler(
    private val config: LemlineConfiguration,
) {

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    @Inject
    lateinit var definitionQueryService: DefinitionQueryService

    suspend fun listDefinitions(
        request: ListDefinitionsRequest,
        principal: GatewayPrincipal,
    ): ListDefinitionsResponse {
        val namespace = optionalString(request.hasNamespace(), request.namespace)
        val allowedNamespaces = allowedNamespacesForRead(config, authorizer, principal, namespace)
        val filter = DefinitionListFilter(
            namespace = namespace,
            name = optionalString(request.hasName(), request.name),
            latestOnly = if (request.hasLatestOnly()) request.latestOnly else true,
            allowedNamespaces = allowedNamespaces,
            pageSize = request.pageSize,
        )

        val pageCursor = if (request.hasPageCursor()) {
            GatewayCursorCodec.decodeDefinitionPageCursor(request.pageCursor).also {
                requireDefinitionPageCursorMatches(it, filter)
            }
        } else {
            null
        }
        val snapshotAt = pageCursor?.snapshotAt?.let(::parseCursorInstant) ?: Instant.now()
        val result = definitionQueryService.listDefinitions(
            filter = filter,
            snapshotAt = snapshotAt,
            page = pageCursor?.toBoundary(),
        )

        val builder = ListDefinitionsResponse.newBuilder()
            .addAllDefinitions(result.definitions.map(::toProtoDefinitionSummary))
            .setTotalCount(result.totalCount)

        result.nextPage?.let {
            builder.setNextPageCursor(
                GatewayCursorCodec.encodeDefinitionPageCursor(
                    DefinitionPageCursor(
                        namespace = filter.namespace,
                        name = filter.name,
                        latestOnly = filter.latestOnly,
                        allowedNamespaces = normalizeAllowedNamespaces(filter.allowedNamespaces),
                        snapshotAt = snapshotAt.toString(),
                        lastNamespace = it.namespace,
                        lastName = it.name,
                        lastVersion = it.version,
                    )
                )
            )
        }

        return builder.build()
    }

    suspend fun getDefinition(
        request: GetDefinitionRequest,
        principal: GatewayPrincipal,
    ): GetDefinitionResponse {
        val namespace = requiredString(request.namespace, "namespace")
        val name = requiredString(request.name, "name")
        authorizer.requireNamespace(principal, namespace)

        val definition = definitionQueryService.getDefinition(
            namespace = namespace,
            name = name,
            version = optionalString(request.hasVersion(), request.version),
        ) ?: throw GatewayNotFoundException("Definition '$namespace/$name' not found")

        return GetDefinitionResponse.newBuilder()
            .setDefinition(toProtoWorkflowDefinition(definition))
            .build()
    }

    private fun requireDefinitionPageCursorMatches(
        cursor: DefinitionPageCursor,
        filter: DefinitionListFilter,
    ) {
        require(cursor.namespace == filter.namespace) { "page_cursor does not match namespace filter" }
        require(cursor.name == filter.name) { "page_cursor does not match name filter" }
        require(cursor.latestOnly == filter.latestOnly) { "page_cursor does not match latest_only filter" }
        require(cursor.allowedNamespaces == normalizeAllowedNamespaces(filter.allowedNamespaces)) {
            "page_cursor does not match authorized namespaces"
        }
    }

    private fun toProtoDefinitionSummary(row: DefinitionSummaryRow): DefinitionSummary {
        val builder = DefinitionSummary.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setIsLatest(row.isLatest)
            .setVersionCount(row.versionCount)
            .setCreatedAt(toProtoTimestamp(row.createdAt))
        row.updatedAt?.let { builder.setUpdatedAt(toProtoTimestamp(it)) }
        return builder.build()
    }

    private fun toProtoWorkflowDefinition(row: DefinitionDetailsRow): WorkflowDefinition {
        val builder = WorkflowDefinition.newBuilder()
            .setNamespace(row.namespace)
            .setName(row.name)
            .setVersion(row.version)
            .setSource(row.source)
            .addAllAvailableVersions(row.availableVersions)
            .setCreatedAt(toProtoTimestamp(row.createdAt))
        row.updatedAt?.let { builder.setUpdatedAt(toProtoTimestamp(it)) }
        return builder.build()
    }
}
