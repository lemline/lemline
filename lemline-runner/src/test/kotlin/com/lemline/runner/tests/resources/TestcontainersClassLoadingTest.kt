// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.RequiresDocker
import com.lemline.runner.tests.profiles.KafkaProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory

@RequiresDocker
@QuarkusTest
@TestProfile(KafkaProfile::class)
class TestcontainersClassLoadingTest {
    @Test
    fun `failed container startup restores the calling thread classloader`() {
        val original = Thread.currentThread().contextClassLoader
        val failure = IllegalStateException("Startup failure")
        val resource = object : TestcontainersResource() {
            override fun startContainers(): Map<String, String> {
                assertSame(original, Thread.currentThread().contextClassLoader)
                throw failure
            }

            override fun stop() = Unit
        }

        assertSame(failure, assertThrows(IllegalStateException::class.java) { resource.start() })
        assertSame(original, Thread.currentThread().contextClassLoader)
    }

    @Test
    fun `Ryuk infrastructure must not retain a Quarkus profile classloader`() {
        val ryukThreads = Thread.getAllStackTraces().keys.filter { it.name == "testcontainers-ryuk" }
        assertEquals(1, ryukThreads.size, "Test profiles must share one Ryuk instance")
        val loaders = listOf(DockerClientFactory::class.java.classLoader, ryukThreads.single().contextClassLoader)
        loaders.forEach { loader ->
            val ancestry = generateSequence(loader) { it.parent }.toList()
            assertFalse(
                ancestry.any { it.javaClass.name == "io.quarkus.bootstrap.classloading.QuarkusClassLoader" },
                "JVM-lived Testcontainers infrastructure retains a Quarkus classloader: $ancestry",
            )
        }
    }
}
