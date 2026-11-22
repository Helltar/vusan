package com.helltar.vusan.config

import com.helltar.vusan.config.llm.LlmProviderConfig

/**
 * Who answers a web search. `WEB_SEARCH_PROVIDER` names exactly one, and only the tools that provider has are
 * offered: none stands in for another when it fails. Without a provider there is no search, only reading a
 * link.
 */
sealed interface WebSearchConfig {
    val name: String

    /** Tavily: web and image search, and page rendering. */
    data class Tavily(val apiKey: String) : WebSearchConfig {
        override val name: String = "tavily"
    }

    /** A SearXNG instance at [url]: web and image search over many engines. */
    data class Searxng(val url: String) : WebSearchConfig {
        override val name: String = "searxng"
    }

    /** Exa: web search with query-relevant excerpts, and page contents. */
    data class Exa(val apiKey: String) : WebSearchConfig {
        override val name: String = "exa"
    }

    /** A researched answer on the ChatGPT plan the chat runs on, from that role's [model]. */
    data class Codex(val model: String) : WebSearchConfig {
        override val name: String = "codex"
    }
}

/**
 * Reads `WEB_SEARCH_PROVIDER` and the one setting its provider needs through [readEnv]. A provider without
 * that setting stops the startup, since naming it says search was wanted. The plan takes no key but answers on
 * the model of the chat or its fallback, the only roles whose model is known to be offered for it.
 */
internal fun resolveWebSearch(
    readEnv: (String) -> String?,
    chat: LlmProviderConfig,
    fallback: LlmProviderConfig?,
): WebSearchConfig? {
    val provider = readEnv("WEB_SEARCH_PROVIDER")?.trim()?.lowercase() ?: return null

    fun required(env: String): String =
        requireNotNull(readEnv(env)?.trim()?.takeIf { it.isNotEmpty() }) { "WEB_SEARCH_PROVIDER=[$provider] needs $env" }

    return when (provider) {
        "tavily" -> WebSearchConfig.Tavily(required("TAVILY_API_KEY"))
        "searxng" -> WebSearchConfig.Searxng(required("SEARXNG_URL"))
        "exa" -> WebSearchConfig.Exa(required("EXA_API_KEY"))

        "codex" -> {
            val codex =
                requireNotNull(listOfNotNull(chat, fallback).firstNotNullOfOrNull { it as? LlmProviderConfig.Codex }) {
                    "WEB_SEARCH_PROVIDER=[codex] searches on the ChatGPT plan the chat runs on: LLM_PROVIDER or LLM_FALLBACK_PROVIDER must be codex"
                }

            WebSearchConfig.Codex(codex.model)
        }

        else -> error("Unsupported WEB_SEARCH_PROVIDER=[$provider]. Supported values: tavily, searxng, exa, codex")
    }
}
