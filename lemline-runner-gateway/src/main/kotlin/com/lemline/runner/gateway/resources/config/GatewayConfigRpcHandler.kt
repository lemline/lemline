// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.resources.config

import com.google.protobuf.Timestamp
import com.lemline.gateway.v1.GetGatewayConfigResponse
import com.lemline.runner.config.LoadedConfigFile
import com.lemline.runner.gateway.auth.GatewayAuthorizer
import com.lemline.runner.gateway.auth.GatewayPrincipal
import io.smallrye.config.SmallRyeConfig
import jakarta.enterprise.context.ApplicationScoped
import java.lang.management.ManagementFactory
import org.eclipse.microprofile.config.ConfigProvider

/** Serves GetGatewayConfig: the running configuration, read-only, secrets excluded. */
@ApplicationScoped
class GatewayConfigRpcHandler(
    private val authorizer: GatewayAuthorizer,
) {
    private val startedAt: Timestamp by lazy {
        val millis = ManagementFactory.getRuntimeMXBean().startTime
        Timestamp.newBuilder().setSeconds(millis / 1000).setNanos(((millis % 1000) * 1_000_000).toInt()).build()
    }

    fun getGatewayConfig(principal: GatewayPrincipal): GetGatewayConfigResponse {
        authorizer.requireScope(principal, CONFIG_SCOPE)
        val config = ConfigProvider.getConfig().unwrap(SmallRyeConfig::class.java)
        return GatewayConfigReport.build(config, LoadedConfigFile.snapshot, startedAt)
    }

    companion object {
        /** Configuration reveals infrastructure (hosts, paths, issuers): it is not implied by other scopes. */
        const val CONFIG_SCOPE = "lemline.config"
    }
}
