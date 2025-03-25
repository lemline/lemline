package com.lemline.swruntime

import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal fun <T : Any> T.logger(): Logger = LoggerFactory.getLogger(this::class.java)

fun Logger.info(e: Throwable? = null, message: () -> String) {
    if (isInfoEnabled) {
        info(message(), e)
    }
}

fun Logger.debug(e: Throwable? = null, message: () -> String) {
    if (isDebugEnabled) {
        debug(message(), e)
    }
}

fun Logger.warn(e: Throwable? = null, message: () -> String) {
    if (isWarnEnabled) {
        warn(message(), e)
    }
}

fun Logger.error(e: Throwable? = null, message: () -> String) {
    if (isErrorEnabled) {
        error(message(), e)
    }
}
