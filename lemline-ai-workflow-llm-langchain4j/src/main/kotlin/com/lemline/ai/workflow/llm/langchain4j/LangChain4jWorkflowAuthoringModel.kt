// SPDX-License-Identifier: BUSL-1.1
package com.lemline.ai.workflow.llm.langchain4j

import com.lemline.ai.workflow.core.llm.WorkflowAuthoringModel

/**
 * Reflection-based bridge so this module compiles even when LangChain4j artifacts
 * are added by consuming applications later.
 */
class LangChain4jWorkflowAuthoringModel(
    private val chatModel: Any,
) : WorkflowAuthoringModel {

    override suspend fun generateWorkflowYaml(
        systemPrompt: String,
        userPrompt: String,
    ): String {
        val prompt = buildString {
            appendLine(systemPrompt)
            appendLine()
            appendLine(userPrompt)
        }
        return invokeModel(prompt)
    }

    private fun invokeModel(prompt: String): String {
        val methods = chatModel::class.java.methods
        val chatMethod = methods.firstOrNull { it.name == "chat" && it.parameterCount == 1 }
        if (chatMethod != null) {
            val result = chatMethod.invoke(chatModel, prompt)
            return extractContent(result)
        }
        val generateMethod = methods.firstOrNull { it.name == "generate" && it.parameterCount == 1 }
        if (generateMethod != null) {
            val result = generateMethod.invoke(chatModel, prompt)
            return extractContent(result)
        }
        error("Unsupported LangChain4j model type: no chat(String) or generate(String) method found.")
    }

    private fun extractContent(result: Any?): String {
        if (result == null) {
            return ""
        }
        if (result is String) {
            return result
        }
        val textMethod = result::class.java.methods.firstOrNull {
            (it.name == "text" || it.name == "content") && it.parameterCount == 0
        }
        if (textMethod != null) {
            val content = textMethod.invoke(result)
            if (content is String) {
                return content
            }
        }

        val contentMethod = result::class.java.methods.firstOrNull { it.name == "content" && it.parameterCount == 0 }
        if (contentMethod != null) {
            val contentObj = contentMethod.invoke(result)
            if (contentObj != null) {
                return extractContent(contentObj)
            }
        }
        return result.toString()
    }
}

object LangChain4jModelFactory {

    fun openAi(
        apiKey: String,
        modelName: String,
        temperature: Double = 0.0,
    ): LangChain4jWorkflowAuthoringModel = buildFromBuilder(
        className = "dev.langchain4j.model.openai.OpenAiChatModel",
        setup = {
            call("apiKey", apiKey)
            call("modelName", modelName)
            call("temperature", temperature)
        },
    )

    fun anthropic(
        apiKey: String,
        modelName: String,
        temperature: Double = 0.0,
    ): LangChain4jWorkflowAuthoringModel = buildFromBuilder(
        className = "dev.langchain4j.model.anthropic.AnthropicChatModel",
        setup = {
            call("apiKey", apiKey)
            call("modelName", modelName)
            call("temperature", temperature)
        },
    )

    fun ollama(
        baseUrl: String,
        modelName: String,
        temperature: Double = 0.0,
    ): LangChain4jWorkflowAuthoringModel = buildFromBuilder(
        className = "dev.langchain4j.model.ollama.OllamaChatModel",
        setup = {
            call("baseUrl", baseUrl)
            call("modelName", modelName)
            call("temperature", temperature)
        },
    )

    private fun buildFromBuilder(
        className: String,
        setup: BuilderContext.() -> Unit,
    ): LangChain4jWorkflowAuthoringModel {
        val clazz = Class.forName(className)
        val builderMethod = clazz.methods.firstOrNull { it.name == "builder" && it.parameterCount == 0 }
            ?: error("Class $className does not expose builder()")
        val builder = builderMethod.invoke(null)
        BuilderContext(builder).apply(setup)
        val buildMethod = builder::class.java.methods.firstOrNull { it.name == "build" && it.parameterCount == 0 }
            ?: error("Builder for $className does not expose build()")
        val model = buildMethod.invoke(builder)
        return LangChain4jWorkflowAuthoringModel(model)
    }

    private class BuilderContext(
        private var builder: Any,
    ) {
        fun call(
            methodName: String,
            value: Any,
        ) {
            val method = builder::class.java.methods.firstOrNull {
                it.name == methodName && it.parameterCount == 1
            } ?: return
            val next = method.invoke(builder, value)
            if (next != null) {
                builder = next
            }
        }
    }
}
