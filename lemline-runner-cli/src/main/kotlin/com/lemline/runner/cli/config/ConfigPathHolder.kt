// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import java.nio.file.Path

/**
 * Holder for the configuration file path.
 * This is set by the main application before Quarkus starts.
 */
object ConfigPathHolder {
    @Volatile
    var configPath: Path? = null
}
