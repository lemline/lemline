package com.lemline.runner.messaging

import com.lemline.common.json.LemlineJson

interface JsonSerializable {
    fun toJsonString(): String = LemlineJson.encodeToString(this)
}
