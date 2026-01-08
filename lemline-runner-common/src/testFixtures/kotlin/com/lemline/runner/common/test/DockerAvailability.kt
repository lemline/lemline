// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.test

import java.io.IOException

object DockerAvailability {

    val isAvailable: Boolean by lazy {
        try {
            val process = ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (_: IOException) {
            false
        } catch (_: InterruptedException) {
            false
        }
    }
}
