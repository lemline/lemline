// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.namespaces.NamespaceScope
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * Test-only [NamespaceScope] that decouples the runner's GUARD scope from its CONSUMPTION scope.
 *
 * The broker test harness (WorkflowConsumerTest suites) needs the runner to:
 * - CONSUME only the `test-in` namespace destinations (driven by `lemline.namespaces=test-in`,
 *   which LemlineConfigSource reads from raw properties — not from this bean), so that the
 *   runner's own emissions are NOT looped back into its consumption;
 * - PROCESS workflows of namespace `test` (the base suite's workflow documents), whose
 *   emissions are routed to the `test` namespace destinations where the test observes them.
 *
 * In production both scopes are the same `lemline.namespaces` set, and the foreign-namespace
 * guard in MessageHandler parks any message whose workflow namespace is not followed. That
 * guard would park every `test` workflow here. Enabling this CDI alternative widens only the
 * guard/ownership scope to accept both namespaces, keeping emissions to namespace `test`
 * observable without loopback.
 *
 * Deliberately NOT annotated with `@Priority`: a prioritized alternative is enabled globally
 * (application-wide) by CDI, which would leak this scope into every `@QuarkusTest` of the
 * module (e.g. NamespaceScopeDefaultsTest asserts the zero-config `[default]` scope). It is
 * selected per profile via `QuarkusTestProfile.getEnabledAlternatives`.
 */
@Alternative
@ApplicationScoped
class TestNamespaceScope : NamespaceScope {

    // "test-in": what the harness runner consumes; "test": the base suite's workflow
    // namespace; "default": namespace of the workflows run by the testcases/* execution
    // suites, so profiles enabling this alternative keep accepting them. This bean only
    // drives the guard and DB scoping — broker consumption comes from the raw
    // `lemline.namespaces` property — so widening it introduces no loopback.
    override val configured: Set<String> = setOf("test-in", "test", "default")
}
