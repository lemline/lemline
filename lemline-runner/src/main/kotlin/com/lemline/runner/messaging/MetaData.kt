// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.values.IDV7

data class MetaData(
    val messageId: IDV7,
) {
    companion object {
        const val MESSAGE_ID = "messageId"
    }
}
