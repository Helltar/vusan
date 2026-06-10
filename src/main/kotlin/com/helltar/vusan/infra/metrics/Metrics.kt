package com.helltar.vusan.infra.metrics

import com.helltar.vusan.common.rethrowIfCancellation
import com.helltar.vusan.tasks.TasksRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

enum class RunTrigger(val label: String) {
    MESSAGE("message"),
    SCHEDULED("scheduled")
}

enum class RunOutcome(val label: String) {
    OK("ok"),
    SILENT("silent"),
    BUSY("busy"),
    PROVIDER_ERROR("provider_error"),
    OVERLOADED("overloaded"),
    UNEXPECTED_ERROR("unexpected_error")
}

enum class DeliveryOutcome(val label: String) {
    OK("ok"),
    FAILED("failed"),
    REPLY_MISSING("reply_missing"),
    PRIVATE_BLOCKED("private_blocked")
}

enum class SenderFallback(val label: String) {
    MARKDOWN_PLAIN("markdown_plain"),
    MARKDOWN_DOCUMENT("markdown_document"),
    MEDIA_DOCUMENT("media_document"),
    MEDIA_GROUP_SPLIT("media_group_split"),
    CAPTION_TEXT("caption_text")
}

enum class TaskFireResult(val label: String) {
    OK("ok"),
    MISSED("missed"),
    ERROR("error")
}

enum class SttResult(val label: String) {
    OK("ok"),
    TOO_LONG("too_long"),
    EMPTY("empty"),
    FAILED("failed")
}

/**
 * Single owner of the Prometheus meter registry and every metric name and tag the bot emits.
 * Call sites record through the typed API below, so the dashboard contract lives in one file.
 *
 * No metric carries a `chat_id`/`user_id` tag: the bot may open up beyond `allowedIds`, and
 * per-peer tags would make label cardinality unbounded. Per-peer visibility comes from the
 * `peers` table gauges instead.
 */
object Metrics {

    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    val agentRunsInflight = AtomicInteger()

    private val chatsKnown = AtomicLong()
    private val usersKnown = AtomicLong()
    private val chatsActive24h = AtomicLong()
    private val chatsActive7d = AtomicLong()
    private val usersActive24h = AtomicLong()
    private val usersActive7d = AtomicLong()
    private val tasksEnabled = AtomicLong()

    init {
        gauge("vusan.agent.runs.inflight") { agentRunsInflight.get() }
        gauge("vusan.peers.chats.known") { chatsKnown.get() }
        gauge("vusan.peers.users.known") { usersKnown.get() }
        gauge("vusan.peers.chats.active", "window", "24h") { chatsActive24h.get() }
        gauge("vusan.peers.chats.active", "window", "7d") { chatsActive7d.get() }
        gauge("vusan.peers.users.active", "window", "24h") { usersActive24h.get() }
        gauge("vusan.peers.users.active", "window", "7d") { usersActive7d.get() }
        gauge("vusan.scheduler.tasks.enabled") { tasksEnabled.get() }
    }

    fun recordInbound(kind: String, chatIsPrivate: Boolean) {
        registry.counter("vusan.inbound.messages", "kind", kind, "chat_type", chatType(chatIsPrivate)).increment()
    }

    fun recordSttTranscription(result: SttResult) {
        registry.counter("vusan.stt.transcriptions", "result", result.label).increment()
    }

    fun recordAgentRun(trigger: RunTrigger, outcome: RunOutcome, duration: Duration? = null) {
        registry.counter("vusan.agent.runs", "trigger", trigger.label, "outcome", outcome.label).increment()

        if (duration != null) {
            // Timer.builder + register is cheap on the hot path: Micrometer caches meters by name+tags.
            Timer.builder("vusan.agent.run.duration")
                .tag("trigger", trigger.label)
                .tag("outcome", outcome.label)
                .serviceLevelObjectives(
                    1.seconds.toJavaDuration(),
                    2.seconds.toJavaDuration(),
                    5.seconds.toJavaDuration(),
                    10.seconds.toJavaDuration(),
                    30.seconds.toJavaDuration(),
                    1.minutes.toJavaDuration(),
                    2.minutes.toJavaDuration(),
                    5.minutes.toJavaDuration()
                )
                .register(registry)
                .record(duration.toJavaDuration())
        }
    }

    fun recordLlmCall(inputTokens: Int?, outputTokens: Int?, totalTokens: Int?) {
        registry.counter("vusan.llm.calls").increment()
        recordTokens("input", inputTokens)
        recordTokens("output", outputTokens)
        recordTokens("total", totalTokens)
    }

    fun recordToolCall(tool: String, isError: Boolean) {
        registry.counter("vusan.tool.calls", "tool", tool, "result", if (isError) "error" else "ok").increment()
    }

    fun recordDelivery(outcome: DeliveryOutcome) {
        registry.counter("vusan.delivery.items", "outcome", outcome.label).increment()
    }

    fun recordSenderFallback(kind: SenderFallback) {
        registry.counter("vusan.delivery.fallbacks", "kind", kind.label).increment()
    }

    fun recordTaskFired(result: TaskFireResult) {
        registry.counter("vusan.scheduler.tasks.fired", "result", result.label).increment()
    }

    fun recordTaskLateness(lateness: Duration) {
        Timer.builder("vusan.scheduler.task.lateness")
            .serviceLevelObjectives(
                1.seconds.toJavaDuration(),
                5.seconds.toJavaDuration(),
                30.seconds.toJavaDuration(),
                1.minutes.toJavaDuration(),
                5.minutes.toJavaDuration(),
                15.minutes.toJavaDuration(),
                30.minutes.toJavaDuration(),
                60.minutes.toJavaDuration()
            )
            .register(registry)
            .record(lateness.coerceAtLeast(Duration.ZERO).toJavaDuration())
    }

    fun launchGaugeRefresh(scope: CoroutineScope, peers: PeersRepository, tasks: TasksRepository): Job =
        scope.launch {
            while (isActive) {
                runCatching {
                    val stats = peers.stats()
                    chatsKnown.set(stats.chatsKnown)
                    usersKnown.set(stats.usersKnown)
                    chatsActive24h.set(stats.chatsActive24h)
                    chatsActive7d.set(stats.chatsActive7d)
                    usersActive24h.set(stats.usersActive24h)
                    usersActive7d.set(stats.usersActive7d)
                    tasksEnabled.set(tasks.countEnabled())
                }.onFailure {
                    it.rethrowIfCancellation()
                    log.warn(it) { "metrics gauge refresh failed" }
                }

                delay(GAUGE_REFRESH_INTERVAL)
            }
        }

    private fun gauge(name: String, vararg tags: String, value: () -> Number) {
        Gauge.builder(name) { value().toDouble() }.tags(*tags).register(registry)
    }

    private fun recordTokens(type: String, tokens: Int?) {
        if (tokens == null || tokens <= 0) return
        registry.counter("vusan.llm.tokens", "type", type).increment(tokens.toDouble())
    }

    private fun chatType(chatIsPrivate: Boolean): String = if (chatIsPrivate) "private" else "group"
}

private val log = KotlinLogging.logger {}

private val GAUGE_REFRESH_INTERVAL = 1.minutes
