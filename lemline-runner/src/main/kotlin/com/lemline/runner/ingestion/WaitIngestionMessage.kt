// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.models.WaitOutboxModel
import com.lemline.runner.models.WithInstance
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("w") // <- type discriminator for polymorphic serialization
data class WaitIngestionMessage(
    @SerialName("m")
    override val model: WaitOutboxModel,
) : IngestionMessage, WithInstance by model
