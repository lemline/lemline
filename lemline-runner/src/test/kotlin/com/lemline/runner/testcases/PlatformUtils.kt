// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases

import com.lemline.core.testcases.impl.WorkflowTestCase

/**
 * Utility functions for platform-specific test filtering.
 */
internal object PlatformUtils {

    /**
     * Determines if a test case should run on the current operating system.
     *
     * Supports tags:
     * - `unix-only`: Runs only on macOS or Linux
     * - `windows-only`: Runs only on Windows
     *
     * @param case The test case to check
     * @return true if the test should run on the current platform
     */
    fun shouldRunOnCurrentPlatform(case: WorkflowTestCase): Boolean {
        val osName = System.getProperty("os.name").lowercase()

        return when {
            "unix-only" in case.tags -> osName.contains("mac") || osName.contains("linux")
            "windows-only" in case.tags -> osName.contains("windows")
            else -> true
        }
    }
}
