// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.outbox.bases

enum class RunStatus {
    PENDING,
    DONE,
    FAILED;

    // Needed by tests
    companion object
}
