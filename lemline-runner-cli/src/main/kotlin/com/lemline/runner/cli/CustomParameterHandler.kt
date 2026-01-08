// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import picocli.CommandLine


class CustomParameterHandler : CommandLine.IParameterExceptionHandler {
    override fun handleParseException(
        ex: CommandLine.ParameterException,
        args: Array<out String?>?
    ): Int {
        val cmd = ex.commandLine
        cmd.err.println("⚠️ ${ex.message}")
        cmd.err.println()
        cmd.usage(cmd.err)
        return cmd.commandSpec.exitCodeOnInvalidInput()
    }
}
