// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.tests.profiles.KafkaProfile
import io.quarkus.bootstrap.classloading.QuarkusClassLoader
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaProfile::class)
class QuarkusMetadataRetentionTest {
    @Test
    fun `ordinary test augmentation releases reload-only metadata before shutdown`() {
        val runtimeLoader = javaClass.classLoader as QuarkusClassLoader
        val augmentationLoader = runtimeLoader.curatedApplication.augmentClassLoader
        for ((owner, name) in listOf(
            "io.quarkus.deployment.dev.RuntimeUpdatesProcessor" to "lastStartIndex",
            "io.quarkus.deployment.steps.ClassTransformingBuildStep" to "lastTransformers",
        )) {
            val field = augmentationLoader.loadClass(owner).getDeclaredField(name)
            field.isAccessible = true
            assertNull(field.get(null), "$owner.$name retains augmentation metadata")
        }
    }
}
