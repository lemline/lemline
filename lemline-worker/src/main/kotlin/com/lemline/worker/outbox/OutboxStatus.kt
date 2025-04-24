// SPDX-License-Identifier: BUSL-1.1
package com.lemline.worker.outbox

enum class OutBoxStatus {
    PENDING,
    SENT,
    FAILED,
}
