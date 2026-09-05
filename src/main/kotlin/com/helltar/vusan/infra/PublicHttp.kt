package com.helltar.vusan.infra

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import okhttp3.Dns
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException

/** Public downloads must never share the client's access to configured internal services. */
fun createPublicHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        engine {
            config {
                proxy(Proxy.NO_PROXY)
                dns(PublicDns())
                followRedirects(false)
                followSslRedirects(false)
                // okhttp bypasses Dns for literal IPs, so check those before it opens a socket.
                addInterceptor { chain ->
                    val host = chain.request().url.host
                    if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                        requirePublicDestination(host, listOf(InetAddress.getByName(host)))
                    }
                    chain.proceed(chain.request())
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

/** The validated addresses are the ones handed to the connection, not a separate DNS preflight. */
internal class PublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        delegate.lookup(hostname).also { requirePublicDestination(hostname, it) }
}

private fun requirePublicDestination(host: String, addresses: List<InetAddress>) {
    if (addresses.isEmpty() || addresses.any { !it.isPublicDestination }) {
        throw UnknownHostException("Refusing a private or local address for [$host]")
    }
}

internal val InetAddress.isPublicDestination: Boolean
    get() {
        val bytes = address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val first = bytes[0]
            val second = bytes[1]
            return first !in setOf(0, 10, 127) && first < 224 &&
                !(first == 100 && second in 64..127) &&
                !(first == 169 && second == 254) &&
                !(first == 172 && second in 16..31) &&
                !(first == 192 && (second == 168 || second == 0 && bytes[2] in setOf(0, 2))) &&
                !(first == 198 && (second in 18..19 || second == 51 && bytes[2] == 100)) &&
                !(first == 203 && second == 0 && bytes[2] == 113)
        }
        // permit native global unicast only; exclude transition/tunnel and special-purpose ranges.
        return bytes.size == 16 && bytes[0] in 0x20..0x3f &&
            !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                (bytes[2] <= 1 || bytes[2] == 0x0d && bytes[3] == 0xb8)) &&
            !(bytes[0] == 0x3f && bytes[1] == 0xff && bytes[2] < 0x10)
    }
