// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.ingestion

import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.IngestionModel
import com.lemline.runner.models.InstanceModel
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * This class contains
 * - a list of [InstanceModel]s that are going to be saved in the database.
 * - a list of [InstanceMessage]s that are going to be sent to the workflow channel (after the db ingestion)
 */
@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("i") // <- type discriminator for polymorphic serialization
data class IngestionMessages(
    @SerialName("db")
    val ingestionModels: List<IngestionModel> = listOf(),
    @SerialName("msg")
    val instanceMessages: List<InstanceMessage> = listOf(),
) : IngestionMessage {

    constructor(model: IngestionModel) : this(ingestionModels = listOf(model))
}
