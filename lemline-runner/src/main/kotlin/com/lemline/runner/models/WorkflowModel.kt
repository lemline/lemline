// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.models

import com.github.f4b6a3.uuid.UuidCreator

data class WorkflowModel(
    override val id: String = UuidCreator.getTimeOrderedEpoch().toString(),

    val name: String,

    val version: String,

    val definition: String
) : UuidV7Entity()
