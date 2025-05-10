// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import io.quarkus.arc.Unremovable
import io.quarkus.runtime.Quarkus
import jakarta.enterprise.context.Dependent
import picocli.CommandLine.Command

@Command(
    name = "start",
    description = ["Start the Lemline server in daemon mode"],
    mixinStandardHelpOptions = true
)
@Unremovable
@Dependent
class StartCommand : Runnable {

    override fun run() {
        Quarkus.waitForExit()
    }
}
