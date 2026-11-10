// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.config

import com.lemline.runner.config.NamespaceScopeValidation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NamespaceScopeValidationTest {

    @Test
    fun `valid namespaces produce no errors`() {
        assertTrue(NamespaceScopeValidation.errorsFor(listOf("default")).isEmpty())
        assertTrue(NamespaceScopeValidation.errorsFor(listOf("acme", "acme-prod", "team-42")).isEmpty())
    }

    @Test
    fun `empty list is rejected`() {
        val errors = NamespaceScopeValidation.errorsFor(emptyList())
        assertTrue(errors.any { it.contains("must not be empty") })
    }

    @Test
    fun `duplicates are rejected`() {
        val errors = NamespaceScopeValidation.errorsFor(listOf("acme", "acme"))
        assertTrue(errors.any { it.contains("duplicated namespace 'acme'") })
    }

    @Test
    fun `blank entries are rejected`() {
        val errors = NamespaceScopeValidation.errorsFor(listOf("acme", " "))
        assertTrue(errors.any { it.contains("blank") })
    }

    @Test
    fun `wildcard and invalid characters are rejected`() {
        listOf("*", "Acme", "acme_prod", "-acme", "acme-", "a.b").forEach { ns ->
            val errors = NamespaceScopeValidation.errorsFor(listOf(ns))
            assertTrue(errors.any { it.contains("invalid namespace '$ns'") }, "expected '$ns' to be invalid")
        }
    }

    @Test
    fun `dash-prefix collisions are detected as warnings not errors`() {
        val namespaces = listOf("acme", "acme-prod", "team")
        assertTrue(NamespaceScopeValidation.errorsFor(namespaces).isEmpty())
        assertEquals(listOf("acme" to "acme-prod"), NamespaceScopeValidation.prefixCollisions(namespaces))
    }

    @Test
    fun `disjoint namespaces have no prefix collisions`() {
        assertTrue(NamespaceScopeValidation.prefixCollisions(listOf("acme", "acmeprod", "team")).isEmpty())
    }
}
