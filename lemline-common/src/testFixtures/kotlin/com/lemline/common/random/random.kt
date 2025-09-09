// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)
@file:Suppress("unused")

package com.lemline.common.random

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowVersion
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

fun String.Companion.random(size: Int = 5) = Random.nextBytes(size).toString()

fun String.Companion.nullableRandom(size: Int = 5) = when (Random.nextBoolean()) {
    true -> String.random(size)
    false -> null
}

fun Instant.Companion.random() = Clock.System.now() + Random.nextInt(-1000, 1000).days

fun Instant.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

fun Int.Companion.random() = Random.nextInt()

fun Int.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

fun WorkflowName.Companion.random() = WorkflowName(String.random())

fun WorkflowVersion.Companion.random() = WorkflowVersion(String.random())

fun IDV7.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

