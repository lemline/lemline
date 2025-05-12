// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instance

import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command

@Unremovable
@Command(
    name = "instance",
    description = ["Manage workflow instances"],
    mixinStandardHelpOptions = true,
    subcommands = [
        InstanceStartCommand::class,
    ]
)
class InstanceCommand
