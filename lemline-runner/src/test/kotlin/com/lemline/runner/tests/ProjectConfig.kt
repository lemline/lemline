// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests

import io.kotest.core.config.AbstractProjectConfig

object ProjectConfig : AbstractProjectConfig() {
    override val autoScanEnabled = false // no classpath scanning
}
