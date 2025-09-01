// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import java.util.*
import kotlinx.serialization.SerialName

interface WithId {
    @SerialName("i")
    val id: UUID
}
