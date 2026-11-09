// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.failures

import com.lemline.runner.cli.GlobalMixin
import io.quarkus.arc.Unremovable
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin

@Unremovable
@Command(
    name = "failures",
    description = ["Manage parked workflow failures"],
    subcommands = [FailuresReplayCommand::class],
)
class FailuresCommand {
    @Mixin
    lateinit var mixin: GlobalMixin
}
