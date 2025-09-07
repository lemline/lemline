// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.models.RetryOutboxModel
import com.lemline.runner.models.WithInstance
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("r") // <- type discriminator for polymorphic serialization
data class RetryIngestionMessage(
    @SerialName("m")
    override val model: RetryOutboxModel,
) : IngestionMessage, WithInstance by model
