package com.helltar.vusan.infra.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics

object MetricsServer {

    private var server: EmbeddedServer<*, *>? = null
    private var gcMetrics: JvmGcMetrics? = null

    fun start(port: Int) {
        check(server == null) { "Metrics server is already running" }

        // JVM binders live here, not in the Metrics object init, so unit tests that scrape the
        // registry see only the bot's own meters.
        gcMetrics = JvmGcMetrics().also { it.bindTo(Metrics.registry) }
        JvmMemoryMetrics().bindTo(Metrics.registry)
        JvmThreadMetrics().bindTo(Metrics.registry)
        ClassLoaderMetrics().bindTo(Metrics.registry)
        ProcessorMetrics().bindTo(Metrics.registry)
        UptimeMetrics().bindTo(Metrics.registry)
        FileDescriptorMetrics().bindTo(Metrics.registry)

        server = embeddedServer(CIO, port = port) { metricsModule() }.start(wait = false)
        log.info { "metrics endpoint listening on port $port" }
    }

    fun stop() {
        server?.stop()
        server = null
        gcMetrics?.close()
        gcMetrics = null
    }
}

fun Application.metricsModule() {
    routing {
        get("/metrics") {
            call.respondText(Metrics.registry.scrape(), ContentType.parse(PROMETHEUS_CONTENT_TYPE))
        }
    }
}

private val log = KotlinLogging.logger {}

private const val PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"
