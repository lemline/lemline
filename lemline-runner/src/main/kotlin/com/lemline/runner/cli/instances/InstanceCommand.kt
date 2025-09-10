// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.instances

import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@ExperimentalSerializationApi
@ExperimentalTime
@Unremovable
@Command(
    name = "instance",
    description = ["Manage workflow instances"],
    subcommands = [InstanceStartCommand::class],
)
class InstanceCommand {
    @Mixin
    lateinit var mixin: GlobalMixin
}
