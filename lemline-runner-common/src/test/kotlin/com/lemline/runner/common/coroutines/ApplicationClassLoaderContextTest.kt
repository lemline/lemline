// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.coroutines

import com.lemline.common.coroutines.ApplicationClassLoaderContext
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

class ApplicationClassLoaderContextTest {
    @Test
    fun `preserves application loader across suspensions and restores worker loader`() = runBlocking {
        val workerLoader = Thread.currentThread().contextClassLoader
        val applicationLoader = object : ClassLoader(workerLoader) {}
        val context = ApplicationClassLoaderContext(applicationLoader)
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "classloader-context-test").apply { contextClassLoader = workerLoader }
        }.asCoroutineDispatcher().use { dispatcher ->
            withContext(dispatcher) {
                assertSame(workerLoader, Thread.currentThread().contextClassLoader)
                withContext(context) {
                    assertSame(applicationLoader, Thread.currentThread().contextClassLoader)
                    withContext(Dispatchers.Default) {
                        delay(1)
                        assertSame(applicationLoader, Thread.currentThread().contextClassLoader)
                    }
                    assertSame(applicationLoader, Thread.currentThread().contextClassLoader)
                }
                assertSame(workerLoader, Thread.currentThread().contextClassLoader)
                assertFailsWith<IllegalStateException> {
                    withContext(context) { error("expected failure") }
                }
                assertSame(workerLoader, Thread.currentThread().contextClassLoader)
            }
        }
    }
}
