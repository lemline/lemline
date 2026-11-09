// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.runner.common.config.LEMLINE_NAMESPACES
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Test profile for in-memory testcase suites.
 *
 * Extends the shared [InMemoryProfile] configuration with the namespaces used by
 * the testcase workflow fixtures (`default`, `examples`, `samples`, `test`), so
 * the namespace guard accepts workflows from all of them (e.g. sub-workflow
 * dependencies registered in the `test` namespace).
 */
class InMemoryTestCaseProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> =
        InMemoryProfile().getConfigOverrides() + mapOf(
            // Follow every namespace used by the testcase workflow fixtures
            LEMLINE_NAMESPACES to "default,examples,samples,test"
        )

    override fun tags(): Set<String> = InMemoryProfile().tags()
}
