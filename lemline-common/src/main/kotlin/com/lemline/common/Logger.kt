// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Extension function to get a Logger for any class.
 */
fun <T : Any> T.logger(): Logger = LoggerFactory.getLogger(this::class.java)

/**
 * Log a message at TRACE level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.trace(e: Throwable? = null, message: () -> String) {
    if (isTraceEnabled) {
        trace(message(), e)
    }
}

/**
 * Log a message at DEBUG level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.debug(e: Throwable? = null, message: () -> String) {
    if (isDebugEnabled) {
        debug(message(), e)
    }
}

/**
 * Log a message at INFO level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.info(e: Throwable? = null, message: () -> String) {
    if (isInfoEnabled) {
        info(message(), e)
    }
}

/**
 * Log a message at WARN level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.warn(e: Throwable? = null, message: () -> String) {
    if (isWarnEnabled) {
        warn(message(), e)
    }
}

/**
 * Log a message at ERROR level.
 *
 * @param e Optional throwable to include in the log
 * @param message Lambda that returns the message to log
 */
fun Logger.error(e: Throwable? = null, message: () -> String) {
    if (isErrorEnabled) {
        error(message(), e)
    }
}
