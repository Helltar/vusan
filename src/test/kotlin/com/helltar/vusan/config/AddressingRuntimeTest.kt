package com.helltar.vusan.config

import com.helltar.vusan.infra.Http
import com.helltar.vusan.llm.ReasoningEffort
import com.helltar.vusan.llm.openai.OpenAiEndpoint
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class AddressingRuntimeTest {

    private fun effortOf(provider: LlmProviderConfig, codexAuth: CodexAuthStore? = null): ReasoningEffort? =
        resolveAddressingRuntime(AddressingConfig(provider, names = emptyList()), codexAuth).options.reasoningEffort

    private fun openAi(model: String, effort: ReasoningEffort? = null) =
        LlmProviderConfig.OpenAi(apiKey = "key", model = model, reasoningEffort = effort, requestTimeout = TIMEOUT)

    private fun anthropic(model: String) = LlmProviderConfig.Anthropic(apiKey = "key", model = model, requestTimeout = TIMEOUT)

    @Test
    fun `a verdict gets the least reasoning its api is known to take`() {
        assertEquals(ReasoningEffort.NONE, effortOf(openAi("gpt-5.6-luna")))
        assertNull(effortOf(openAi("gpt-4.1-mini")), "a model that does not reason is sent no effort")
        assertEquals(ReasoningEffort.LOW, effortOf(anthropic("claude-haiku-5-5")))
        assertNull(effortOf(anthropic("claude-haiku-4-5-20251001")), "a dated claude model takes no effort")

        val compatible =
            LlmProviderConfig.OpenAiCompatible(
                baseUrl = "https://api.deepseek.com",
                apiKey = "key",
                model = "deepseek-flash",
                endpoint = OpenAiEndpoint.COMPLETIONS,
                requestTimeout = TIMEOUT,
            )

        assertNull(effortOf(compatible), "a compatible server's models are left to their own default")
    }

    // `none` is rarely among what a codex model takes, and a 400 there would silence addressing for good
    @Test
    fun `a codex model gets the least the plan's catalog lists, and its own default without one`() {
        val auth = CodexAuthStore(Http.createClient(MockEngine { error("no calls expected") }))
        val listed = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)

        assertEquals(ReasoningEffort.LOW, effortOf(LlmProviderConfig.Codex(model = "gpt-5.6-luna", supportedEfforts = listed, requestTimeout = TIMEOUT), auth))
        assertNull(effortOf(LlmProviderConfig.Codex(model = "gpt-5.6-luna", requestTimeout = TIMEOUT), auth))
    }

    @Test
    fun `an effort the deployment set wins`() {
        assertEquals(ReasoningEffort.HIGH, effortOf(openAi("gpt-5.6-luna", effort = ReasoningEffort.HIGH)))
    }

    private companion object {
        val TIMEOUT = 30.seconds
    }
}
