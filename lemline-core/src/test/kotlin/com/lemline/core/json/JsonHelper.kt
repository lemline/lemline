// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.json

fun String.toJsonElement() = LemlineJson.json.parseToJsonElement(this)
