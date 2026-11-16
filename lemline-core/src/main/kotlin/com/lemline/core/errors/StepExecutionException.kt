// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.errors

import com.lemline.core.states.NodeStack
import kotlinx.coroutines.CancellationException

/** Carries the latest immutable stack to the common error boundary. Never serialized. */
class StepExecutionException(val nodeStack: NodeStack, val failure: Exception) : RuntimeException(failure)

internal suspend fun <T> withExecutionStack(stack: NodeStack, action: suspend () -> T): T = try {
    action()
} catch (e: StepExecutionException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw StepExecutionException(stack, e)
}
