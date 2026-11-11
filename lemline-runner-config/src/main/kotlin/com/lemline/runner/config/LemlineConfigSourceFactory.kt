// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.ConfigSourceContext
import io.smallrye.config.ConfigSourceFactory
import org.eclipse.microprofile.config.spi.ConfigSource

/** Generates connector and datasource settings from the effective application configuration. */
class LemlineConfigSourceFactory : ConfigSourceFactory {
    override fun getConfigSources(context: ConfigSourceContext): Iterable<ConfigSource> =
        listOf(LemlineConfigSource(context))
}
