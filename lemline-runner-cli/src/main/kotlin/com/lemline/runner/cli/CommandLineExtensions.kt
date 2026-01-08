// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli

import picocli.CommandLine

/**
 * Extension function to configure a CommandLine instance with standard Lemline settings.
 *
 * This configures:
 * - Auto-width for usage help
 * - Case-insensitive enum values
 * - Strict argument matching (no unmatched arguments allowed)
 * - Custom exception handlers for user-friendly error messages
 */
fun CommandLine.setup(): CommandLine = this
    .setUsageHelpAutoWidth(true)
    .setCaseInsensitiveEnumValuesAllowed(true)
    .setUnmatchedArgumentsAllowed(false)
    .apply {
        parameterExceptionHandler = CustomParameterHandler()
        executionExceptionHandler = CustomExceptionHandler()
    }
