// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import org.eclipse.microprofile.reactive.messaging.Message as ReactiveMessage

interface MessageHandler {
    suspend fun handleMessage(message: ReactiveMessage<String>)
}
