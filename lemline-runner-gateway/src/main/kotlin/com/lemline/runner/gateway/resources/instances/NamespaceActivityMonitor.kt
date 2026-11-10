// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.instances

import com.lemline.common.logger.logger
import com.lemline.runner.common.config.ANALYTICS_LIFECYCLE_EVENTS_TABLE
import com.lemline.runner.common.config.AnalyticsType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.config.analyticsSchemaResolved
import com.lemline.runner.config.analyticsTypeResolved
import com.lemline.runner.gateway.analytics.AnalyticsDataSourceProvider
import com.lemline.core.lifecycleevents.LifecycleEventType
import com.lemline.runner.gateway.persistence.GatewayJdbcSupport
import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loud-failure signals for namespaces nobody follows.
 *
 * A workflow started in a namespace no runner follows stays pending forever — durable, but
 * blocked. Passive dashboard polling is not enough for first contact: this monitor emits an
 * active WARN + counter at start time when the target namespace shows no runner activity in
 * the analytics read model (only `workflow.created` events, which the gateway itself emits).
 */
@ApplicationScoped
class NamespaceActivityMonitor(
    private val config: LemlineConfiguration,
    private val registry: MeterRegistry,
    private val namespaceScope: NamespaceScope,
) {
    private val logger = logger()

    @Inject
    lateinit var analyticsDataSourceProvider: AnalyticsDataSourceProvider

    private val analyticsQualifiedTable by lazy {
        GatewayJdbcSupport.qualifiedAnalyticsTable(
            analyticsType = AnalyticsType.fromConfigValue(config.analyticsTypeResolved),
            schema = config.analyticsSchemaResolved,
            table = ANALYTICS_LIFECYCLE_EVENTS_TABLE
        )
    }

    /** Namespaces confirmed active once — never re-checked (no nominal-path DB cost). */
    private val activeNamespaces = ConcurrentHashMap.newKeySet<String>()

    /**
     * On a pinned gateway, lifecycle events without a namespace are invisible by design
     * (excluded by every `IN (...)` filter). Surface their existence once at boot.
     */
    @Suppress("unused")
    fun onStart(@Observes @Priority(10) event: StartupEvent) {
        val pin = namespaceScope.configured ?: return
        runCatching {
            val sql = "SELECT 1 FROM $analyticsQualifiedTable WHERE lemline_workflow_namespace IS NULL LIMIT 1"
            analyticsDataSourceProvider.require().connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            logger.warn {
                                "Analytics store contains lifecycle events without a namespace: they are " +
                                    "invisible on this pinned gateway (pin: $pin)."
                            }
                        }
                    }
                }
            }
        }.onFailure { logger.debug(it) { "NULL-namespace analytics check skipped" } }
    }

    /**
     * Called after a start is accepted. Cheap after the first hit per namespace; on a miss,
     * one EXISTS on the (namespace, type) index. Never fails the start.
     */
    suspend fun onInstanceAccepted(namespace: String) {
        if (namespace in activeNamespaces) return
        runCatching {
            if (hasRunnerActivity(namespace)) {
                activeNamespaces.add(namespace)
            } else {
                logger.warn {
                    "Workflow started in namespace '$namespace' but no runner activity has ever been " +
                        "recorded for it — if no runner follows this namespace (lemline.namespaces), " +
                        "the instance will stay pending."
                }
                registry.counter(METRIC_INACTIVE_NAMESPACE_STARTS, "namespace", namespace).increment()
            }
        }.onFailure { logger.debug(it) { "Namespace activity check skipped for '$namespace'" } }
    }

    private suspend fun hasRunnerActivity(namespace: String): Boolean = withContext(Dispatchers.IO) {
        val sql = """
            SELECT 1 FROM $analyticsQualifiedTable
            WHERE lemline_workflow_namespace = ? AND type <> ?
            LIMIT 1
        """.trimIndent()
        analyticsDataSourceProvider.require().connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, namespace)
                stmt.setString(2, LifecycleEventType.WORKFLOW_CREATED.type)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    companion object {
        const val METRIC_INACTIVE_NAMESPACE_STARTS = "lemline.gateway.start.inactive_namespace.total"
    }
}
