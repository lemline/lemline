// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.namespaces

import com.lemline.common.values.WorkflowNamespace

/**
 * The set of namespaces this process is scoped to.
 *
 * Every runner follows an explicit set of namespaces (`lemline.namespaces`): it only consumes,
 * executes and persists workflows belonging to them. When the property is absent, consumption
 * falls back to the single [DEFAULT_NAMESPACE] so a zero-config runner works out of the box.
 *
 * The gateway reads [configured] directly as an optional pin: absent means "serve all namespaces".
 */
interface NamespaceScope {

    /** Namespaces explicitly configured via `lemline.namespaces`, or null when the property is absent. */
    val configured: Set<String>?

    /** Consumption and persistence scope: [configured], or the default namespace when not configured. */
    val followed: Set<String>
        get() = configured ?: setOf(DEFAULT_NAMESPACE)

    /** Whether this process is responsible for workflows of the given namespace. */
    fun owns(namespace: WorkflowNamespace): Boolean = namespace.toString() in followed

    companion object {
        const val DEFAULT_NAMESPACE = "default"

        /**
         * Scope of a process without explicit configuration: consumption follows [DEFAULT_NAMESPACE],
         * persistence is unscoped ([configured] = null). Also the default for repositories
         * constructed outside CDI (tests).
         */
        val UNSCOPED: NamespaceScope = object : NamespaceScope {
            override val configured: Set<String>? = null
        }
    }
}
