// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.utils

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * Maps each element of the list to a suspend function and executes them concurrently.
 * If any of the suspend functions fail, the first encountered exception is thrown, but the other are still running
 *
 * @param block A suspend function that defines the transformation logic for each element of the list.
 * @return A list of transformed elements if all suspend functions complete successfully.
 * @throws Throwable If any of the suspend functions throw an exception, the first exception is rethrown.
 */
suspend fun <S, T> List<S>.mapAwaitAllFailFast(block: suspend (S) -> T): List<T> =
    map { suspend { block(it) } }.awaitAllFailFast()


/**
 * Suspends and concurrently executes all suspend functions in the list, returning a list of results.
 * If any of the suspend functions fail, the first encountered exception is thrown, but the other are still running
 *
 * @return A list of results produced by the successful execution of suspend functions if all complete without failure.
 * @throws Throwable If any suspend function encounters an exception, the first one is rethrown.
 */
private suspend fun <T> List<suspend () -> T>.awaitAllFailFast(): List<T> {
    if (isEmpty()) return emptyList()

    val firstFailure = CompletableDeferred<Throwable?>()

    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

    val deferred = map { block ->
        scope.async { block() }
    }

    // Watcher: if all deferred complete successfully, mark firstFailure as null ("no error").
    scope.launch {
        try {
            deferred.awaitAll()
            if (!firstFailure.isCompleted) firstFailure.complete(null)
        } catch (e: Throwable) {
            // One of the deferred failed
            firstFailure.complete(e)
        }
    }

    // Throw the exception, if any
    firstFailure.await()?.let { throw it }

    // return the result
    return deferred.awaitAll()
}

/**
 * Executes a transformation function on each element of the list in parallel
 * and returns the result of the first successful execution (the remaining still running).
 * If all transformations fail, the exception from the last failure is rethrown.
 *
 * @param S The type of the elements in the list.
 * @param T The type of the result produced by the transformation function.
 * @param block The suspendable transformation function applied to each element of the list.
 * @return The result of the first successful execution of the transformation function.
 * @throws Exception If all transformations fail, rethrows the exception from the last failure.
 */
suspend fun <S, T> List<S>.mapAwaitFirstFailSlow(block: suspend (S) -> T): T =
    map { suspend { block(it) } }.awaitFirstFailSlow()

/**
 * Executes a list of suspendable functions in parallel and returns the result of the first successful execution.
 * The remaining are still running. If all functions fail, the exception from the last failure is rethrown.
 *
 * @return The result of the first successfully executed function in the list.
 * @throws Exception If all functions in the list fail, rethrows the exception from the last failure.
 */
private suspend fun <T> List<suspend () -> T>.awaitFirstFailSlow(): T {
    require(isNotEmpty()) { "List must not be empty" }

    val firstSuccess = CompletableDeferred<T>()
    val failureCount = AtomicInteger(0)

    val scope = CoroutineScope(currentCoroutineContext() + SupervisorJob())

    forEach { block ->
        scope.launch {
            try {
                block().also {
                    if (!firstSuccess.isCompleted) firstSuccess.complete(it)
                }
            } catch (e: Throwable) {
                if (failureCount.incrementAndGet() == size && !firstSuccess.isCompleted) {
                    firstSuccess.completeExceptionally(e)
                }
            }
        }
    }

    // return the result or throws the exception from the last failure
    return firstSuccess.await()
}
