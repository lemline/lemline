// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.coroutines

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement

/** Keeps CDI configuration and service loading tied to the application across dispatcher switches. */
class ApplicationClassLoaderContext(
    private val classLoader: ClassLoader? = Thread.currentThread().contextClassLoader,
) : ThreadContextElement<ClassLoader?>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ApplicationClassLoaderContext>

    override fun updateThreadContext(context: CoroutineContext): ClassLoader? {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ClassLoader?) {
        Thread.currentThread().contextClassLoader = oldState
    }
}
