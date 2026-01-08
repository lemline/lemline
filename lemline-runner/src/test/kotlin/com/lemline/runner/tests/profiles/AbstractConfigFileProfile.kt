// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.tests.resources.ConfigFileTestResource
import io.quarkus.test.junit.QuarkusTestProfile

abstract class AbstractConfigFileProfile(private val configFile: String) : QuarkusTestProfile {

    override fun testResources(): List<QuarkusTestProfile.TestResourceEntry> =
        listOf(
            QuarkusTestProfile.TestResourceEntry(
                ConfigFileTestResource::class.java,
                mapOf(ConfigFileTestResource.CONFIG_FILE_ARG to configFile)
            )
        )
}

class CustomConfigFileProfile : AbstractConfigFileProfile("config-test/custom-config.yaml")

class MinimalConfigFileProfile : AbstractConfigFileProfile("config-test/minimal-config.yaml")
