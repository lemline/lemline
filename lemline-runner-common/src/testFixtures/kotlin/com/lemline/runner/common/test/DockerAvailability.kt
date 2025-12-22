// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import org.testcontainers.DockerClientFactory

/**
 * Utility to check if Docker is available on the current machine.
 * This is used to conditionally run Testcontainers-based tests.
 */
object DockerAvailability {

    /**
     * Returns true if Docker is available and running.
     * Caches the result to avoid repeated checks.
     */
    val isAvailable: Boolean by lazy {
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (e: Exception) {
            false
        }
    }
}
