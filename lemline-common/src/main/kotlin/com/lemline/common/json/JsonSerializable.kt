// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

interface JsonSerializable {
    fun toJsonString() = LemlineJson.encodeToString(this)
}
