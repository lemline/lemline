// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.ingestion

import com.lemline.runner.models.FailureModel
import com.lemline.runner.models.WithInstance
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@ExperimentalSerializationApi
@ExperimentalTime
@Serializable
@SerialName("f") // <- type discriminator for polymorphic serialization
data class FailureIngestionMessage(
    @SerialName("m")
    override val model: FailureModel
) : IngestionMessage, WithInstance by model
