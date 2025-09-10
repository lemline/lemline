// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import picocli.CommandLine


class CustomExceptionHandler : CommandLine.IExecutionExceptionHandler {
    override fun handleExecutionException(
        ex: Exception,
        cmd: CommandLine,
        parseResult: CommandLine.ParseResult?
    ): Int {
        cmd.err.println("❌ ${ex.message}")
        return cmd.commandSpec.exitCodeOnExecutionException()
    }
}
