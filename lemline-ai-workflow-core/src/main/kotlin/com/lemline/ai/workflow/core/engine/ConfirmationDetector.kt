// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.engine

class ConfirmationDetector {

    fun isAffirmative(text: String): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) {
            return false
        }
        return affirmativeTokens.any { normalized == it || normalized.startsWith("$it ") }
    }

    private companion object {
        private val affirmativeTokens = setOf(
            "yes",
            "y",
            "ok",
            "okay",
            "confirm",
            "confirmed",
            "looks good",
            "go ahead",
            "oui",
            "valide",
            "c'est bon",
        )
    }
}
