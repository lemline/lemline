// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.common.json.LemlineJson
import com.lemline.runner.models.WithInstance
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface IngestionMessage : WithInstance {
    val model: WithInstance

    companion object {
        fun fromJsonString(json: String): IngestionMessage = LemlineJson.decodeFromString(json)
    }
}
