// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.core.inference

import com.lemline.ai.workflow.common.model.InputContractSource
import com.lemline.ai.workflow.common.model.InputContractSummary
import com.lemline.ai.workflow.common.model.InputField
import com.lemline.ai.workflow.common.model.InputFieldType
import com.lemline.ai.workflow.common.model.IntentSnapshot
import com.lemline.ai.workflow.common.model.WorkflowAssumption

data class InputContractInference(
    val summary: InputContractSummary,
    val assumptions: List<WorkflowAssumption>,
    val unresolvedCriticalAmbiguities: Int,
    val complete: Boolean,
)

class InputContractInferer {

    private val typedFieldPattern = Regex("""\b([a-zA-Z_][a-zA-Z0-9_]*)\s*:\s*(string|number|integer|boolean|object|array)\b""")
    private val jsonFieldPattern = Regex(""""([a-zA-Z_][a-zA-Z0-9_]*)"\s*:""")

    fun infer(
        previous: InputContractSummary,
        intent: IntentSnapshot,
        userMessage: String,
        minimumConfidence: Double,
    ): InputContractInference {
        val explicitTypedFields = parseTypedFields(userMessage)
        val explicitJsonFields = parseJsonFieldNames(userMessage)
        val inferredFields = inferFromIntentAndKeywords(intent, userMessage)

        val mergedFields = linkedMapOf<String, InputField>()
        explicitTypedFields.forEach { mergedFields[it.name] = it.copy(required = true) }
        explicitJsonFields.forEach { name ->
            if (!mergedFields.containsKey(name)) {
                mergedFields[name] = InputField(
                    name = name,
                    type = InputFieldType.STRING,
                    required = false,
                    description = "Type not provided by user; defaulted conservatively to string.",
                )
            }
        }
        inferredFields.forEach { field ->
            mergedFields.putIfAbsent(field.name, field)
        }

        val fields = if (mergedFields.isEmpty()) {
            listOf(
                InputField(
                    name = "payload",
                    type = InputFieldType.OBJECT,
                    required = true,
                    description = "Fallback payload containing workflow request data.",
                )
            )
        } else {
            mergedFields.values.toList()
        }

        val source = when {
            explicitTypedFields.isNotEmpty() || explicitJsonFields.isNotEmpty() -> {
                if (inferredFields.isNotEmpty()) {
                    InputContractSource.MIXED
                } else {
                    InputContractSource.PROVIDED
                }
            }

            previous.fields.isNotEmpty() -> previous.source
            else -> InputContractSource.INFERRED
        }

        val ambiguities = computeAmbiguities(
            explicitJsonWithoutTypeCount = explicitJsonFields.count { name -> explicitTypedFields.none { it.name == name } },
            userMessage = userMessage,
            intent = intent,
        )
        val confidence = computeConfidence(source, ambiguities, fields)
        val requiredFields = fields.filter { it.required }.map { it.name }
        val assumptions = buildAssumptions(source, fields, ambiguities)

        val summary = InputContractSummary(
            fields = fields,
            requiredFields = requiredFields,
            format = "json",
            source = source,
            confidence = confidence,
            humanSummary = buildHumanSummary(fields, source, confidence, ambiguities),
        )

        return InputContractInference(
            summary = summary,
            assumptions = assumptions,
            unresolvedCriticalAmbiguities = ambiguities,
            complete = requiredFields.isNotEmpty() && confidence >= minimumConfidence && ambiguities == 0,
        )
    }

    private fun parseTypedFields(message: String): List<InputField> = typedFieldPattern
        .findAll(message)
        .map {
            val name = it.groupValues[1]
            val type = parseType(it.groupValues[2])
            InputField(
                name = name,
                type = type,
                required = true,
                description = "Provided by user in the conversation.",
            )
        }
        .toList()

    private fun parseJsonFieldNames(message: String): Set<String> = jsonFieldPattern
        .findAll(message)
        .map { it.groupValues[1] }
        .toSet()

    private fun inferFromIntentAndKeywords(intent: IntentSnapshot, message: String): List<InputField> {
        val lower = message.lowercase()
        val fields = linkedMapOf<String, InputField>()

        if (intent.requiresLoop || lower.containsAny("items", "batch", "list")) {
            fields["items"] = InputField(
                name = "items",
                type = InputFieldType.ARRAY,
                required = true,
                description = "Collection to iterate on in loop operations.",
            )
        }
        if (intent.requiresCondition || lower.containsAny("condition", "if", "approval")) {
            fields["shouldProceed"] = InputField(
                name = "shouldProceed",
                type = InputFieldType.BOOLEAN,
                required = true,
                description = "Condition flag used for branching.",
            )
        }
        if (intent.requiresHttpCall || lower.containsAny("http", "api", "customer", "order")) {
            fields["resourceId"] = InputField(
                name = "resourceId",
                type = InputFieldType.STRING,
                required = true,
                description = "Identifier used in outbound API calls.",
            )
        }
        if (lower.containsAny("email", "mail")) {
            fields["email"] = InputField(
                name = "email",
                type = InputFieldType.STRING,
                required = true,
                description = "Email contact referenced by business request.",
                example = "alice@example.com",
            )
        }
        if (lower.containsAny("amount", "price", "total")) {
            fields["amount"] = InputField(
                name = "amount",
                type = InputFieldType.NUMBER,
                required = true,
                description = "Monetary amount used by rules and external services.",
                example = "199.95",
            )
        }

        return fields.values.toList()
    }

    private fun parseType(value: String): InputFieldType = when (value.lowercase()) {
        "string" -> InputFieldType.STRING
        "number" -> InputFieldType.NUMBER
        "integer" -> InputFieldType.INTEGER
        "boolean" -> InputFieldType.BOOLEAN
        "object" -> InputFieldType.OBJECT
        "array" -> InputFieldType.ARRAY
        else -> InputFieldType.STRING
    }

    private fun computeAmbiguities(
        explicitJsonWithoutTypeCount: Int,
        userMessage: String,
        intent: IntentSnapshot,
    ): Int {
        var ambiguity = explicitJsonWithoutTypeCount
        val lower = userMessage.lowercase()
        if (lower.containsAny("maybe", "whatever", "some data", "etc")) {
            ambiguity += 1
        }
        if (intent.purpose == null) {
            ambiguity += 1
        }
        return ambiguity
    }

    private fun computeConfidence(
        source: InputContractSource,
        ambiguities: Int,
        fields: List<InputField>,
    ): Double {
        val base = when (source) {
            InputContractSource.PROVIDED -> 0.90
            InputContractSource.MIXED -> 0.75
            InputContractSource.INFERRED -> 0.58
        }
        val densityBonus = (fields.size.coerceAtMost(5) * 0.02)
        val penalty = ambiguities * 0.10
        return (base + densityBonus - penalty).coerceIn(0.0, 1.0)
    }

    private fun buildAssumptions(
        source: InputContractSource,
        fields: List<InputField>,
        ambiguities: Int,
    ): List<WorkflowAssumption> {
        val assumptions = mutableListOf<WorkflowAssumption>()
        if (source != InputContractSource.PROVIDED) {
            fields
                .filter { it.required }
                .forEach { field ->
                    assumptions += WorkflowAssumption(
                        id = "inferred-input-${field.name}",
                        statement = "Required input field '${field.name}' inferred as ${field.type.schemaType}.",
                    )
                }
        }
        if (ambiguities > 0) {
            assumptions += WorkflowAssumption(
                id = "input-ambiguity",
                statement = "Input types or requiredness remained partially ambiguous after clarification.",
            )
        }
        return assumptions
    }

    private fun buildHumanSummary(
        fields: List<InputField>,
        source: InputContractSource,
        confidence: Double,
        ambiguities: Int,
    ): String {
        val required = fields.filter { it.required }.joinToString(", ") { "${it.name}:${it.type.schemaType}" }
        return "Format=json, source=${source.name.lowercase()}, required=[$required], " +
            "confidence=${"%.2f".format(confidence)}, ambiguities=$ambiguities."
    }
}

private fun String.containsAny(vararg tokens: String): Boolean = tokens.any { contains(it) }
