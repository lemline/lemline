// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.json

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import io.serverlessworkflow.api.types.ErrorDetails
import io.serverlessworkflow.api.types.ErrorTitle

// SDK 7.35.3 uses a non-DOTALL ".*" for literal error strings. The DSL permits
// line breaks, so preserve those literals while keeping the SDK's serializers.
@JsonDeserialize(using = ErrorDetailsDeserializer::class)
internal abstract class ErrorDetailsMixIn : io.serverlessworkflow.api.types.jackson.ErrorDetailsMixIn()

@JsonDeserialize(using = ErrorTitleDeserializer::class)
internal abstract class ErrorTitleMixIn : io.serverlessworkflow.api.types.jackson.ErrorTitleMixIn()

private val sdkStringPattern = Regex("^\\s*\\$\\{.+\\}\\s*$|.*")

internal class ErrorDetailsDeserializer : JsonDeserializer<ErrorDetails>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): ErrorDetails {
        val value = parser.readValueAs(String::class.java)
        return if (sdkStringPattern.matches(value)) ErrorDetails().withExpressionErrorDetails(value)
        else object : ErrorDetails() {
            override fun get(): String = value
            override fun getLiteralErrorDetails(): String = value
        }
    }
}

internal class ErrorTitleDeserializer : JsonDeserializer<ErrorTitle>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): ErrorTitle {
        val value = parser.readValueAs(String::class.java)
        return if (sdkStringPattern.matches(value)) ErrorTitle().withExpressionErrorTitle(value)
        else object : ErrorTitle() {
            override fun get(): String = value
            override fun getLiteralErrorTitle(): String = value
        }
    }
}
