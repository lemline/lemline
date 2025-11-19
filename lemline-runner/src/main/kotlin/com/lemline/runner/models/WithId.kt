// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.lemline.common.values.IDV7

interface WithId {
    /** Unique time-sortable identifier for this entity */
    val id: IDV7
}
