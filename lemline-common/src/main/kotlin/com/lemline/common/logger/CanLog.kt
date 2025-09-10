// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.logger

interface CanLog {
    fun trace(e: Throwable? = null, message: () -> String)

    fun debug(e: Throwable? = null, message: () -> String)

    fun info(e: Throwable? = null, message: () -> String)

    fun warn(e: Throwable? = null, message: () -> String)

    fun error(e: Throwable? = null, message: () -> String)
}
