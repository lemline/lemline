// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.logger

interface CanSuspendLog {
    suspend fun trace(e: Throwable? = null, message: suspend () -> String)

    suspend fun debug(e: Throwable? = null, message: suspend () -> String)

    suspend fun info(e: Throwable? = null, message: suspend () -> String)

    suspend fun warn(e: Throwable? = null, message: suspend () -> String)

    suspend fun error(e: Throwable? = null, message: suspend () -> String)
}
