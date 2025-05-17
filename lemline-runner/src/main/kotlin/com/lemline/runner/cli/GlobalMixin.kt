// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Option

/**
 * Mixin class containing global configuration options
 */
@Command(
    mixinStandardHelpOptions = true,
    versionProvider = VersionProvider::class,
)
class GlobalMixin {
    @ArgGroup(
        exclusive = true,
        heading = "Log level options:%n"
    )
    var logLevelGroup: LogLevelGroup? = null

    @Option(
        names = ["-c", "--config"],
        description = ["Specify configuration file location"],
        paramLabel = "<config>",
    )
    var configFile: String? = null

    // Static nested class to hold the mutually exclusive log level options
    class LogLevelGroup {
        @Option(
            names = ["--debug"],
            description = ["Set log level to DEBUG"],
        )
        var debugMode: Boolean = false

        @Option(
            names = ["--info"],
            description = ["Set log level to INFO"],
        )
        var infoMode: Boolean = false

        @Option(
            names = ["--warn"],
            description = ["Set log level to WARN"],
        )
        var warnMode: Boolean = false

        @Option(
            names = ["--error"],
            description = ["Set log level to ERROR"],
        )
        var errorMode: Boolean = false
    }
}
