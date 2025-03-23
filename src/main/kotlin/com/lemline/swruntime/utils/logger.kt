package com.lemline.swruntime.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

fun <T : Any> T.logger(): Logger = LoggerFactory.getLogger(this::class.java)

fun Logger.info(message: () -> String) {
    if (isInfoEnabled) {
        info(message())
    }
}

fun Logger.debug(message: () -> String) {
    if (isDebugEnabled) {
        debug(message())
    }
}

fun Logger.warn(message: () -> String) {
    if (isWarnEnabled) {
        warn(message())
    }
}

fun Logger.error(message: () -> String) {
    if (isErrorEnabled) {
        error(message())
    }
}
