// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.nio.file.Path

/** How the external configuration file was found. */
enum class ConfigFileLocator {
    /** Passed with `--config` / `-c`. */
    OPTION,

    /** Named by the `LEMLINE_CONFIG` environment variable. */
    ENVIRONMENT,

    /** Found at a default location (`./.lemline.yaml`, `~/.config/lemline/config.yaml`, `~/.lemline.yaml`). */
    DEFAULT_PATH,
}

/**
 * Holder for the external configuration file path.
 * This is set by the application before Quarkus starts.
 */
object ConfigPathHolder {
    @Volatile
    var configPath: Path? = null

    @Volatile
    var locator: ConfigFileLocator? = null
}
