package com.helltar.vusan.infra.metrics

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MetricsServerTest {

    @Test
    fun `metrics endpoint serves the prometheus exposition`() = testApplication {
        application { metricsModule() }

        val response = client.get("/metrics")

        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.bodyAsText(), "vusan_agent_runs_inflight")
    }
}
