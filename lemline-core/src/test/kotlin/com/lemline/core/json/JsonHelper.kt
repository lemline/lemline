// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.json

import com.lemline.common.json.LemlineJson

fun String.toJsonElement() = LemlineJson.json.parseToJsonElement(this)
