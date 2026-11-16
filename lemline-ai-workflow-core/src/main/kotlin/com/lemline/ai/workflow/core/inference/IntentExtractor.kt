// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.inference

import com.lemline.ai.workflow.common.model.IntentSnapshot

class IntentExtractor {

    fun extract(previous: IntentSnapshot, userMessage: String): IntentSnapshot {
        val normalized = userMessage.lowercase()
        val purpose = previous.purpose ?: extractPurpose(userMessage)

        val requiresHttpCall = previous.requiresHttpCall || normalized.containsAny("http", "api", "webhook", "call")
        val requiresLoop = previous.requiresLoop || normalized.containsAny("loop", "for each", "foreach", "batch")
        val requiresCondition = previous.requiresCondition || normalized.containsAny("if", "condition", "sinon", "else")
        val requiresEventWait = previous.requiresEventWait || normalized.containsAny("wait", "event", "listen")
        val requiresRetry = previous.requiresRetry || normalized.containsAny("retry", "reessayer", "timeout")

        val outputExpectation = previous.outputExpectation ?: extractOutputExpectation(userMessage)
        val ambiguityCount = computeAmbiguities(
            purpose = purpose,
            requiresHttpCall = requiresHttpCall,
            requiresLoop = requiresLoop,
            requiresCondition = requiresCondition,
        )

        return previous.copy(
            purpose = purpose,
            requiresHttpCall = requiresHttpCall,
            requiresLoop = requiresLoop,
            requiresCondition = requiresCondition,
            requiresEventWait = requiresEventWait,
            requiresRetry = requiresRetry,
            outputExpectation = outputExpectation,
            unresolvedCriticalAmbiguities = ambiguityCount,
        )
    }

    private fun extractPurpose(message: String): String? {
        val cleaned = message.trim()
        return cleaned.takeIf { it.length >= 18 }
    }

    private fun extractOutputExpectation(message: String): String? {
        val lower = message.lowercase()
        if (lower.containsAny("result", "output", "retour", "response")) {
            return message.trim()
        }
        return null
    }

    private fun computeAmbiguities(
        purpose: String?,
        requiresHttpCall: Boolean,
        requiresLoop: Boolean,
        requiresCondition: Boolean,
    ): Int {
        var ambiguities = 0
        if (purpose == null) {
            ambiguities += 1
        }
        if (!requiresHttpCall && !requiresLoop && !requiresCondition) {
            ambiguities += 1
        }
        return ambiguities
    }
}

private fun String.containsAny(vararg tokens: String): Boolean = tokens.any { contains(it) }
