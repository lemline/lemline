// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.gateway

import io.quarkus.arc.Unremovable
import jakarta.enterprise.context.Dependent
import picocli.CommandLine.Command

@Command(
    name = "gateway",
    description = ["manage the gRPC gateway"],
    subcommands = [GatewayStartCommand::class]
)
@Unremovable
@Dependent
class GatewayCommand
