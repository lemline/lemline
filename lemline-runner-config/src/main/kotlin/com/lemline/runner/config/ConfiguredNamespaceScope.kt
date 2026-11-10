// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.common.namespaces.NamespaceScope
import jakarta.enterprise.context.ApplicationScoped

/**
 * [NamespaceScope] backed by the `lemline.namespaces` configuration property.
 * Entries are validated at startup by [NamespaceScopeValidator].
 */
@ApplicationScoped
class ConfiguredNamespaceScope(config: LemlineConfiguration) : NamespaceScope {

    override val configured: Set<String>? = config.declaredNamespaces?.toSet()
}
