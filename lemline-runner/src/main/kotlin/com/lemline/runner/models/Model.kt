// SPDX-License-Identifier: BUSL-1.1

package com.lemline.runner.models

import com.lemline.common.json.JsonSerializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
@ExperimentalSerializationApi
@JsonClassDiscriminator("t") // <- type discriminator for polymorphic serialization
sealed interface IngestionModel : JsonSerializable
