// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.config

import com.lemline.runner.config.shared.ConfigPathHolder as SharedConfigPathHolder
import java.nio.file.Path

/**
 * Backward-compatible access point for the shared configuration file path holder.
 */
object ConfigPathHolder {
    var configPath: Path?
        get() = SharedConfigPathHolder.configPath
        set(value) {
            SharedConfigPathHolder.configPath = value
        }
}
