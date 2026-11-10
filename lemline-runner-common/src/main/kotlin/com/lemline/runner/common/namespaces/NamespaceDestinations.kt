// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.namespaces

import com.lemline.common.values.WorkflowNamespace

/**
 * Message channel families that have one broker destination per namespace.
 * Lifecycle events are excluded on purpose: they flow to a central trusted sink on a shared destination.
 */
enum class DestinationType(val suffix: String) {
    COMMANDS("commands"),
    EVENTS("events"),
    CLOUDEVENTS("cloudevents"),
}

/**
 * Destination naming convention: one broker destination per namespace, `<prefix>-<namespace>-<type>`
 * (e.g. `lemline-acme-commands`). Namespace-first so all destinations of a namespace share the
 * `<prefix>-<namespace>-` prefix, enabling per-namespace broker ACLs with a single prefix rule.
 *
 * A pure function of the message's namespace: producers need no routing configuration,
 * readers choose which namespaces they follow.
 */
fun destinationFor(prefix: String, namespace: String, type: DestinationType): String =
    "$prefix-$namespace-${type.suffix}"

fun destinationFor(prefix: String, namespace: WorkflowNamespace, type: DestinationType): String =
    destinationFor(prefix, namespace.toString(), type)
