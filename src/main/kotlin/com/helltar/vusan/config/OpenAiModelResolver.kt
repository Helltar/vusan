package com.helltar.vusan.config

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.llm.LLModel
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField

object OpenAiModelResolver {

    private val modelsByKey: Map<String, LLModel> by lazy {
        OpenAIModels.Chat::class.memberProperties
            .asSequence()
            .filter { it.returnType.classifier == LLModel::class }
            .mapNotNull { it.javaField?.get(OpenAIModels.Chat) as? LLModel }
            .associateBy { it.id.lowercase() }
    }

    fun resolve(rawValue: String): LLModel =
        requireNotNull(modelsByKey[normalize(rawValue)]) {
            "Unsupported OpenAI model '$rawValue'. Supported values: ${modelsByKey.keys.sorted().joinToString()}"
        }

    private fun normalize(value: String): String =
        value
            .trim()
            .lowercase()
            .replace('_', '-')
}
