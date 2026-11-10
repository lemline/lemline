// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.config.LemlineConfiguration
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Without `lemline.namespaces`, a runner follows the single "default" namespace
 * (zero-config experience) and the gateway pin is absent.
 */
@QuarkusTest
class NamespaceScopeDefaultsTest {

    @Inject
    lateinit var config: LemlineConfiguration

    @Inject
    lateinit var namespaceScope: NamespaceScope

    @Test
    fun `namespaces property is absent by default`() {
        assertTrue(config.namespaces().isEmpty)
        assertEquals("lemline", config.messaging().prefix())
    }

    @Test
    fun `scope falls back to the default namespace`() {
        assertNull(namespaceScope.configured)
        assertEquals(setOf(NamespaceScope.DEFAULT_NAMESPACE), namespaceScope.followed)
        assertTrue(namespaceScope.owns(WorkflowNamespace("default")))
        assertFalse(namespaceScope.owns(WorkflowNamespace("acme")))
    }
}
