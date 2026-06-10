package com.helltar.vusan.infra.metrics

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the Prometheus exposition contract the Grafana dashboard depends on: every recording API
 * must surface its metric under the expected name with the expected tags. The registry is a
 * process-wide singleton, so assertions check presence, not exact values.
 */
class MetricsTest {

    @Test
    fun `recorded metrics appear in the prometheus scrape with expected names and tags`() {
        Metrics.recordInbound(kind = "text", chatIsPrivate = true)
        Metrics.recordInbound(kind = "photo", chatIsPrivate = false)
        Metrics.recordSttTranscription(SttResult.OK)
        Metrics.recordAgentRun(RunTrigger.MESSAGE, RunOutcome.OK, duration = 2.seconds)
        Metrics.recordAgentRun(RunTrigger.MESSAGE, RunOutcome.BUSY)
        Metrics.recordLlmCall(inputTokens = 100, outputTokens = 50, totalTokens = 150)
        Metrics.recordToolCall(tool = "sendMessage", isError = false)
        Metrics.recordToolCall(tool = "webSearch", isError = true)
        Metrics.recordDelivery(DeliveryOutcome.OK)
        Metrics.recordSenderFallback(SenderFallback.MARKDOWN_PLAIN)
        Metrics.recordTaskFired(TaskFireResult.OK)
        Metrics.recordTaskLateness(3.seconds)

        val scrape = Metrics.registry.scrape()

        assertContains(scrape, """vusan_inbound_messages_total{chat_type="private",kind="text"}""")
        assertContains(scrape, """vusan_inbound_messages_total{chat_type="group",kind="photo"}""")
        assertContains(scrape, """vusan_stt_transcriptions_total{result="ok"}""")
        assertContains(scrape, """vusan_agent_runs_total{outcome="ok",trigger="message"}""")
        assertContains(scrape, """vusan_agent_runs_total{outcome="busy",trigger="message"}""")
        assertContains(scrape, """vusan_agent_run_duration_seconds_bucket{outcome="ok",trigger="message",le="5.0"}""")
        assertContains(scrape, "vusan_llm_calls_total")
        assertContains(scrape, """vusan_llm_tokens_total{type="input"}""")
        assertContains(scrape, """vusan_llm_tokens_total{type="output"}""")
        assertContains(scrape, """vusan_llm_tokens_total{type="total"}""")
        assertContains(scrape, """vusan_tool_calls_total{result="ok",tool="sendMessage"}""")
        assertContains(scrape, """vusan_tool_calls_total{result="error",tool="webSearch"}""")
        assertContains(scrape, """vusan_delivery_items_total{outcome="ok"}""")
        assertContains(scrape, """vusan_delivery_fallbacks_total{kind="markdown_plain"}""")
        assertContains(scrape, """vusan_scheduler_tasks_fired_total{result="ok"}""")
        assertContains(scrape, """vusan_scheduler_task_lateness_seconds_bucket{le="5.0"}""")
    }

    @Test
    fun `gauges are registered even before the first refresh`() {
        val scrape = Metrics.registry.scrape()

        assertContains(scrape, "vusan_agent_runs_inflight")
        assertContains(scrape, "vusan_peers_chats_known")
        assertContains(scrape, "vusan_peers_users_known")
        assertContains(scrape, """vusan_peers_chats_active{window="24h"}""")
        assertContains(scrape, """vusan_peers_chats_active{window="7d"}""")
        assertContains(scrape, """vusan_peers_users_active{window="24h"}""")
        assertContains(scrape, """vusan_peers_users_active{window="7d"}""")
        assertContains(scrape, "vusan_scheduler_tasks_enabled")
    }
}
