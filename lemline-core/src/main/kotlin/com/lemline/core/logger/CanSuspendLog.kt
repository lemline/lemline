package com.lemline.core.logger

interface CanSuspendLog {
    suspend fun trace(e: Throwable? = null, message: suspend () -> String)

    suspend fun debug(e: Throwable? = null, message: suspend () -> String)

    suspend fun info(e: Throwable? = null, message: suspend () -> String)

    suspend fun warn(e: Throwable? = null, message: suspend () -> String)

    suspend fun error(e: Throwable? = null, message: suspend () -> String)
}
