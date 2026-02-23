// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.nio.file.Path

/**
 * Holder for the external configuration file path.
 * This is set by the application before Quarkus starts.
 */
object ConfigPathHolder {
    @Volatile
    var configPath: Path? = null
}
