// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.common.json.LemlineJson
import com.lemline.runner.messaging.JsonSerializable
import com.lemline.runner.models.WithInstanceInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface DatabaseMessage : WithInstanceInfo, JsonSerializable {

    override fun toJsonString(): String = LemlineJson.encodeToString(this)

    companion object {
        fun fromJsonString(json: String): DatabaseMessage = LemlineJson.decodeFromString(json)
    }
}
