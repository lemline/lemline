// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import io.smallrye.config.AbstractLocationConfigSourceLoader
import io.smallrye.config.PropertiesConfigSource
import io.smallrye.config.source.yaml.YamlConfigSource
import java.net.URL
import org.eclipse.microprofile.config.spi.ConfigSource

/**
 * Adds the contents of the file pointed to by the property
 *
 *     custom.config.uri = file:/etc/my-app/tenant-overrides.yml
 *
 * to the Config at **ordinal 275** (overrides profile files, but
 * stays below env-vars / system-props).
 *
 * Supported file extensions: .properties, .yml, .yaml
 */
class ExtraFileConfigFactory : AbstractLocationConfigSourceLoader() {

    /** Tell the superclass which file types we parse */
    override fun getFileExtensions(): Array<String> =
        arrayOf("properties", "yml", "yaml")

    /**
     * Build the list of additional ConfigSources, or an empty list
     * if custom.config.uri isn't defined.
     */
    fun getConfigSources(uri: String?): List<ConfigSource> = if (uri.isNullOrBlank()) {
        emptyList()
    } else {
        loadConfigSources(uri, 275, Thread.currentThread().contextClassLoader)
    }

    override fun loadConfigSource(url: URL, ordinal: Int): ConfigSource =
        when {
            url.path.endsWith(".yaml", true) || url.path.endsWith(".yml", true) ->
                YamlConfigSource(url, ordinal)         // uses SnakeYAML under the hood
            else ->
                PropertiesConfigSource(url, ordinal)   // .properties (default)
        }
}
