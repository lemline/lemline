// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.common.logger.logger
import com.lemline.runner.common.namespaces.NamespaceScope
import io.quarkus.runtime.StartupEvent
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import kotlin.jvm.optionals.getOrNull

/**
 * Fail-fast validation of `lemline.namespaces`.
 *
 * Namespace values become part of broker destination names (`<prefix>-<namespace>-<type>`),
 * so they must satisfy both the DSL namespace pattern and topic/queue naming constraints.
 */
@ApplicationScoped
class NamespaceScopeValidator(private val config: LemlineConfiguration) {

    private val logger = logger()

    @Suppress("unused")
    fun onStart(@Observes @Priority(0) event: StartupEvent) {
        val declared = config.namespaces().getOrNull()

        // Always announce the scope: a workflow started in a namespace no runner follows stays
        // pending — this line is the first thing to check when diagnosing a stuck instance.
        logger.info {
            "Following namespaces: ${declared ?: "[${NamespaceScope.DEFAULT_NAMESPACE}] (lemline.namespaces not set)"}"
        }

        if (declared == null) return

        val errors = NamespaceScopeValidation.errorsFor(declared)
        if (errors.isNotEmpty()) {
            throw IllegalStateException(
                "Invalid 'lemline.namespaces' configuration:\n - " + errors.joinToString("\n - ")
            )
        }

        NamespaceScopeValidation.prefixCollisions(declared).forEach { (shorter, longer) ->
            logger.warn {
                "Namespace '$shorter' is a dash-prefix of '$longer': a broker prefix ACL on " +
                    "'${config.messaging().prefix()}-$shorter-*' would also cover the destinations of " +
                    "'$longer'. This check only sees this process's namespaces — run the ops prefix-" +
                    "collision check across all deployments when provisioning ACLs."
            }
        }
    }
}

/**
 * Pure validation rules, separated from CDI for direct unit testing.
 */
object NamespaceScopeValidation {

    /** DSL namespace pattern, also safe for Kafka topics, RabbitMQ names and PGMQ queues. */
    val NAMESPACE_PATTERN = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?")

    fun errorsFor(namespaces: List<String>): List<String> = buildList {
        if (namespaces.isEmpty()) {
            add("the list must not be empty when set (remove the property to fall back to 'default')")
        }
        namespaces.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            add("duplicated namespace '$it'")
        }
        namespaces.filter { it.isBlank() }.forEach { _ ->
            add("blank namespace entry")
        }
        namespaces.filter { it.isNotBlank() && !NAMESPACE_PATTERN.matches(it) }.forEach {
            add(
                "invalid namespace '$it': must match '${NAMESPACE_PATTERN.pattern}' " +
                    "(lowercase alphanumerics and dashes; wildcards like '*' are not allowed)"
            )
        }
    }

    /** Pairs (shorter, longer) where `shorter` is a dash-prefix of `longer` (ACL prefix leak). */
    fun prefixCollisions(namespaces: List<String>): List<Pair<String, String>> {
        val distinct = namespaces.distinct()
        return distinct.flatMap { shorter ->
            distinct.filter { longer -> longer != shorter && longer.startsWith("$shorter-") }
                .map { longer -> shorter to longer }
        }
    }
}
