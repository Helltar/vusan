package com.helltar.vusan.config

import com.helltar.vusan.config.llm.LlmProviderConfig
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class WebSearchConfigTest {

    private val openAi = LlmProviderConfig.OpenAi(apiKey = "key", model = "chat-model", requestTimeout = 60.seconds)
    private val codex = LlmProviderConfig.Codex(model = "plan-model", requestTimeout = 60.seconds)

    private fun resolve(env: Map<String, String>, chat: LlmProviderConfig = openAi, fallback: LlmProviderConfig? = null) =
        resolveWebSearch(env::get, chat, fallback)

    @Test
    fun `a key alone turns no search on`() {
        assertNull(resolve(mapOf("TAVILY_API_KEY" to "t", "SEARXNG_URL" to "http://searxng", "EXA_API_KEY" to "e")))
    }

    @Test
    fun `the named provider takes only its own setting`() {
        val env = mapOf("TAVILY_API_KEY" to "t", "SEARXNG_URL" to "http://searxng", "EXA_API_KEY" to "e")

        assertEquals(WebSearchConfig.Tavily("t"), resolve(env + ("WEB_SEARCH_PROVIDER" to "tavily")))
        assertEquals(WebSearchConfig.Searxng("http://searxng"), resolve(env + ("WEB_SEARCH_PROVIDER" to " SearXNG ")))
        assertEquals(WebSearchConfig.Exa("e"), resolve(env + ("WEB_SEARCH_PROVIDER" to "exa")))
    }

    @Test
    fun `a provider without its setting stops the startup and names it`() {
        val e = assertFailsWith<IllegalArgumentException> { resolve(mapOf("WEB_SEARCH_PROVIDER" to "exa", "TAVILY_API_KEY" to "t")) }

        assertContains(e.message.orEmpty(), "EXA_API_KEY")
    }

    @Test
    fun `the plan searches on the model of the chat or its fallback`() {
        val env = mapOf("WEB_SEARCH_PROVIDER" to "codex")

        assertEquals(WebSearchConfig.Codex("plan-model"), resolve(env, chat = codex))
        assertEquals(WebSearchConfig.Codex("plan-model"), resolve(env, chat = openAi, fallback = codex))
    }

    @Test
    fun `the plan cannot search where no chat role runs on it`() {
        val e = assertFailsWith<IllegalArgumentException> { resolve(mapOf("WEB_SEARCH_PROVIDER" to "codex")) }

        assertContains(e.message.orEmpty(), "LLM_PROVIDER")
    }

    @Test
    fun `an unknown provider stops the startup`() {
        val e = assertFailsWith<IllegalStateException> { resolve(mapOf("WEB_SEARCH_PROVIDER" to "bing")) }

        assertContains(e.message.orEmpty(), "tavily, searxng, exa, codex")
    }
}
