// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.gateway

import com.lemline.common.logger.logger
import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import io.quarkus.runtime.Quarkus
import jakarta.enterprise.context.Dependent
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

@Command(
    name = "start",
    description = ["start the gRPC gateway server"],
)
@Unremovable
@Dependent
class GatewayStartCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Option(names = ["-p", "--port"], description = ["The gRPC port to use (overrides config)"])
    var port: Int? = null

    val logger = logger()

    override fun run() {
        logger.info { "Starting gRPC gateway server..." }
        Quarkus.waitForExit()
    }
}
