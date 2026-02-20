// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Unremovable
@Command(
    name = "config",
    description = ["Manage Lemline configuration"],
    subcommands = [
        ConfigShowCommand::class,
        ConfigCreateCommand::class,
    ],
)
class ConfigCommand {
    @Mixin
    lateinit var mixin: GlobalMixin
}
