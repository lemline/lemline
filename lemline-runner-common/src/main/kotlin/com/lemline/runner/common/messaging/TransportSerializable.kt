// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.common.messaging

interface TransportSerializable {
    fun toTransportPayload(): String
}
