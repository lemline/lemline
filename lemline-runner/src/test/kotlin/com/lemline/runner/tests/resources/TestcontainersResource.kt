// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.resources

import com.lemline.runner.common.test.DockerAvailability
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import org.testcontainers.DockerClientFactory

/** Starts JVM-lived Docker/Ryuk threads without inheriting a Quarkus profile's context classloader. */
abstract class TestcontainersResource : QuarkusTestResourceLifecycleManager {
    final override fun start(): Map<String, String> {
        if (!DockerAvailability.isAvailable) return emptyMap()

        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        try {
            // application.properties loads Testcontainers core in the shared parent classloader.
            thread.contextClassLoader = DockerClientFactory::class.java.classLoader
            DockerClientFactory.instance().client()
        } finally {
            thread.contextClassLoader = previous
        }
        // Broker clients and application config still need the profile's classloader.
        return startContainers()
    }

    protected abstract fun startContainers(): Map<String, String>
}
