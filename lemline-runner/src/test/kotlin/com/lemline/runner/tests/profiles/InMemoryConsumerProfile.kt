// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Profile for the WorkflowConsumer in-memory suite: the shared [InMemoryProfile]
 * configuration plus the [TestNamespaceScope] alternative.
 *
 * The base suite's workflows use namespace `test`, and the foreign-namespace guard in
 * MessageHandler parks any workflow whose namespace is not followed (`[default]` when
 * `lemline.namespaces` is absent). [TestNamespaceScope] widens the guard scope so those
 * workflows are processed. The in-memory connector binds by channel name, so destination
 * routing metadata is inert and no `lemline.namespaces` consumption config is needed.
 *
 * Composed (rather than subclassed) because [InMemoryProfile] lives in testFixtures and
 * is final.
 */
class InMemoryConsumerProfile : QuarkusTestProfile {

    private val base = InMemoryProfile()

    override fun getConfigOverrides(): Map<String, String> = base.getConfigOverrides()

    /**
     * Widens the foreign-namespace guard scope to accept the base suite's `test` workflows
     * — see [TestNamespaceScope].
     */
    override fun getEnabledAlternatives(): Set<Class<*>> = setOf(TestNamespaceScope::class.java)

    override fun tags(): Set<String> = base.tags()
}
