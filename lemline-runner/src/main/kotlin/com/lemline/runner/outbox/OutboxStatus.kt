// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox

enum class OutBoxStatus {
    PENDING,
    SENT,
    FAILED,
}
