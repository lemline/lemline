// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class)
@file:Suppress("unused")

package com.lemline.common.random

import com.lemline.common.values.IDV7
import com.lemline.common.values.WorkflowName
import com.lemline.common.values.WorkflowNamespace
import com.lemline.common.values.WorkflowVersion
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Generates a random ByteArray string of a specified size.
 * @param size The size of the random ByteArray to generate, defaults to 5
 * @return A string representation of the random ByteArray
 */
fun String.Companion.random(size: Int = 5) = Random.nextBytes(size).toString()

/**
 * Generates a random nullable string of a specified size.
 * @param size The size of the random string to generate, defaults to 5
 * @return A random string or null based on a random boolean
 */
fun String.Companion.nullableRandom(size: Int = 5) = when (Random.nextBoolean()) {
    true -> String.random(size)
    false -> null
}

/**
 * Generates a random Instant within -1000 to 1000 days from now.
 * @return A random Instant
 */
fun Instant.Companion.random() = Clock.System.now() + Random.nextInt(-1000, 1000).days

/**
 * Generates a random nullable Instant.
 * @return A random Instant or null based on a random boolean
 */
fun Instant.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

/**
 * Generates a random Int.
 * @return A random Int
 */
fun Int.Companion.random() = Random.nextInt()

/**
 * Generates a random nullable Int.
 * @return A random Int or null based on a random boolean
 */
fun Int.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}

/**
 * Generates a random WorkflowNamespace.
 * @return A WorkflowNamespace with a random string value
 */
fun randomWorkflowNamespace() = WorkflowNamespace(String.random())

/**
 * Generates a random WorkflowName.
 * @return A WorkflowName with a random string value
 */
fun randomWorkflowName() = WorkflowName(String.random())

/**
 * Generates a random WorkflowVersion.
 * @return A WorkflowVersion with a random string value
 */
fun randomWorkflowVersion() = WorkflowVersion(String.random())

/**
 * Generates a random nullable IDV7.
 * @return A random IDV7 or null based on a random boolean
 */
fun IDV7.Companion.nullableRandom() = when (Random.nextBoolean()) {
    true -> random()
    false -> null
}
