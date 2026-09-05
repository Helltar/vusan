package com.helltar.vusan.infra

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.*
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PublicHttpTest {
    @Test
    fun `local reserved and transition addresses are not public destinations`() {
        listOf(
            "0.0.0.0", "127.0.0.1", "10.1.2.3", "172.16.1.2", "192.168.1.2",
            "169.254.169.254", "100.64.0.1", "100.127.255.255", "198.18.0.1",
            "192.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.1.1.1", "255.255.255.255",
            "::", "::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1", "64:ff9b::a00:1",
            "2002:0a00:0001::1", "2001::1", "2001:db8::1", "3fff::1"
        ).forEach { assertFalse(InetAddress.getByName(it).isPublicDestination, it) }
        listOf("1.1.1.1", "8.8.8.8", "93.184.216.34", "2001:4860:4860::8888", "2606:4700:4700::1111")
            .forEach { assertTrue(InetAddress.getByName(it).isPublicDestination, it) }
    }

    @Test
    fun `connection DNS rejects mixed answers and changed answers`() {
        val public = InetAddress.getByName("93.184.216.34")
        val local = InetAddress.getByName("127.0.0.1")
        var answers = listOf(public)
        val dns = PublicDns(Dns { answers })
        assertEquals(answers, dns.lookup("download.example"))
        answers = listOf(public, local)
        assertFailsWith<UnknownHostException> { dns.lookup("download.example") }
        answers = listOf(local)
        assertFailsWith<UnknownHostException> { dns.lookup("download.example") }
        answers = emptyList()
        assertFailsWith<UnknownHostException> { dns.lookup("download.example") }
    }

    @Test
    fun `the real download engine refuses loopback literals and DNS without connecting`() = runBlocking {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            createPublicHttpClient().use { http ->
                for (host in listOf("127.0.0.1", "localhost", "[::ffff:127.0.0.1]")) {
                    assertFails { http.get("http://$host:${server.address.port}/private") }
                }
            }
            assertEquals(0, hits.get())
        } finally {
            server.stop(0)
        }
    }
}
