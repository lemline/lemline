// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.models.ParentOutboxModel
import com.lemline.runner.models.WithInstance
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("p") // <- type discriminator for polymorphic serialization
data class ParentIngestionMessage(
    @SerialName("m")
    override val model: ParentOutboxModel
) : IngestionMessage, WithInstance by model
