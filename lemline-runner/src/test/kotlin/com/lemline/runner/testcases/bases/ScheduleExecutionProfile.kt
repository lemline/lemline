// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.bases

import com.lemline.runner.common.config.LEMLINE_GATEWAY_AUTHENTICATION_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_ENABLED
import com.lemline.runner.common.config.LEMLINE_GATEWAY_TLS_ENABLED
import io.quarkus.test.junit.QuarkusTestProfile

/** Preserve the broker profile and enable the production relay for starts accepted by the gateway. */
abstract class ScheduleExecutionProfile(private val brokerProfile: QuarkusTestProfile) :
    QuarkusTestProfile {
    // Java default interface methods are not forwarded by Kotlin interface delegation.
    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> = brokerProfile.testResources()

    override fun tags(): Set<String> = brokerProfile.tags()

    override fun getConfigOverrides(): Map<String, String> = brokerProfile.configOverrides + mapOf(
        LEMLINE_GATEWAY_ENABLED to "true",
        LEMLINE_GATEWAY_AUTHENTICATION_ENABLED to "false",
        LEMLINE_GATEWAY_TLS_ENABLED to "false",
        "quarkus.grpc.server.test-port" to "0",
    )
}
