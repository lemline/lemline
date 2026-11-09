// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.common.values.WorkflowNamespace
import com.lemline.runner.common.namespaces.DestinationType
import com.lemline.runner.common.namespaces.NamespaceScope
import com.lemline.runner.common.namespaces.destinationFor
import com.lemline.runner.config.LemlineConfiguration
import com.lemline.runner.tests.profiles.NamespacesConfigMappingProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestProfile(NamespacesConfigMappingProfile::class)
@QuarkusTest
class NamespacesConfigMappingTest {

    @Inject
    lateinit var config: LemlineConfiguration

    @Inject
    lateinit var namespaceScope: NamespaceScope

    @Test
    fun `namespaces and prefix keys are accepted and mapped`() {
        assertEquals(listOf("acme", "acme-prod"), config.namespaces().orElseThrow())
        assertEquals("lem", config.messaging().prefix())
    }

    @Test
    fun `namespace scope reflects the configured namespaces`() {
        assertEquals(setOf("acme", "acme-prod"), namespaceScope.configured)
        assertEquals(setOf("acme", "acme-prod"), namespaceScope.followed)
        assertTrue(namespaceScope.owns(WorkflowNamespace("acme")))
        assertTrue(namespaceScope.owns(WorkflowNamespace("acme-prod")))
        assertFalse(namespaceScope.owns(WorkflowNamespace("other")))
    }

    @Test
    fun `destination naming convention is namespace-first`() {
        assertEquals("lem-acme-commands", destinationFor("lem", "acme", DestinationType.COMMANDS))
        assertEquals("lem-acme-events", destinationFor("lem", WorkflowNamespace("acme"), DestinationType.EVENTS))
        assertEquals("lem-acme-cloudevents", destinationFor("lem", "acme", DestinationType.CLOUDEVENTS))
    }
}
