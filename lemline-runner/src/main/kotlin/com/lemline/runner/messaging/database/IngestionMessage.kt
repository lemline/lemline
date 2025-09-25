// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.database

import com.lemline.runner.messaging.instances.InstanceMessage
import com.lemline.runner.models.InstanceModel
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * This class contains
 * - a list of [InstanceModel]s that are going to be saved in the database.
 * - a list of [InstanceMessage]s that are going to be sent to the workflow channel (after the db ingestion)
 */
@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("i") // <- type discriminator for polymorphic serialization
data class IngestionMessage(
    @SerialName("db")
    val instanceModels: List<InstanceModel> = listOf(),
    @SerialName("msg")
    val instanceMessages: List<InstanceMessage> = listOf(),
) : DatabaseMessage {

    constructor(model: InstanceModel) : this(instanceModels = listOf(model))

    init {
        require(instanceModels.map { it.workflowId }
            .distinct().size == 1) { "All models must be from the same workflow" }
    }

    @Transient
    override val workflowId = instanceModels.first().workflowId

    @Transient
    override val workflowNamespace = instanceModels.first().workflowNamespace

    @Transient
    override val workflowName = instanceModels.first().workflowName

    @Transient
    override val workflowVersion = instanceModels.first().workflowVersion
}
