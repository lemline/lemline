// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.cli.config.ConfigPathHolder
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.nio.file.Path

class ConfigFileTestResource : QuarkusTestResourceLifecycleManager {

    private var previousConfigPath: Path? = null
    private var configFile: String = DEFAULT_CONFIG_FILE

    override fun init(initArgs: MutableMap<String, String>) {
        initArgs[CONFIG_FILE_ARG]?.let { configFile = it }
    }

    override fun start(): Map<String, String> {
        previousConfigPath = ConfigPathHolder.configPath

        val resource = javaClass.classLoader.getResource(configFile)
            ?: throw IllegalStateException("Test config file not found: $configFile")

        ConfigPathHolder.configPath = Path.of(resource.toURI())

        return emptyMap()
    }

    override fun stop() {
        ConfigPathHolder.configPath = previousConfigPath
    }

    companion object {
        const val CONFIG_FILE_ARG = "configFile"
        const val DEFAULT_CONFIG_FILE = "config-test/custom-config.yaml"
    }
}
