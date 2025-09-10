// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.logger

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Extension function to get a Logger for any class.
 */

fun <T : Any> T.logger() = Logger(this::class.java)

class Logger(klass: Class<*>) : CanLog {
    val logger: Logger = LoggerFactory.getLogger(klass)

    /**
     * Log a message at TRACE level.
     *
     * @param e Optional throwable to include in the log
     * @param message Lambda that returns the message to log
     */
    override fun trace(e: Throwable?, message: () -> String) = with(logger) {
        if (isTraceEnabled) trace(message(), e)
    }

    /**
     * Log a message at DEBUG level.
     *
     * @param e Optional throwable to include in the log
     * @param message Lambda that returns the message to log
     */
    override fun debug(e: Throwable?, message: () -> String) = with(logger) {
        if (isDebugEnabled) debug(message(), e)
    }

    /**
     * Log a message at INFO level.
     *
     * @param e Optional throwable to include in the log
     * @param message Lambda that returns the message to log
     */
    override fun info(e: Throwable?, message: () -> String) = with(logger) {
        if (isInfoEnabled) info(message(), e)
    }

    /**
     * Log a message at WARN level.
     *
     * @param e Optional throwable to include in the log
     * @param message Lambda that returns the message to log
     */
    override fun warn(e: Throwable?, message: () -> String) = with(logger) {
        if (isWarnEnabled) warn(message(), e)
    }

    /**
     * Log a message at ERROR level.
     *
     * @param e Optional throwable to include in the log
     * @param message Lambda that returns the message to log
     */
    override fun error(e: Throwable?, message: () -> String) = with(logger) {
        if (isErrorEnabled) error(message(), e)
    }
}
