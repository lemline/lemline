// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.lemline.runner.cli.LemlineMixin
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Unremovable
@Command(
    name = "instance",
    description = ["Manage workflow instances"],
    subcommands = [
        InstanceStartCommand::class,
    ],
)
class InstanceCommand {
    @Mixin
    lateinit var mixin: LemlineMixin
}
