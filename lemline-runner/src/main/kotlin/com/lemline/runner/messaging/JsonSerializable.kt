// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging

import com.lemline.common.json.LemlineJson

interface JsonSerializable {
    fun toJsonString(): String = LemlineJson.encodeToString(this)
}
