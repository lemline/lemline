// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.json.JsonSerializable
import com.lemline.common.json.LemlineJson
import com.lemline.common.values.WithOptionalWorkflowInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface DatabaseMessage : WithOptionalWorkflowInfo, JsonSerializable {

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(json: String): DatabaseMessage = LemlineJson.decodeFromString(json)
    }
}
