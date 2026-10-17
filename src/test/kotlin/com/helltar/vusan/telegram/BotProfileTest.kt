package com.helltar.vusan.telegram

import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient

class BotProfileTest {

    @Test
    fun `the profile name leads, followed by the handle without its bot suffix`() {
        assertEquals(listOf("Robin", "robin_helper"), profile(username = "robin_helperBot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robo"), profile(username = "robo_bot").addressingNames(emptyList()))
    }

    @Test
    fun `configured names replace the ones read off the handle`() {
        assertEquals(listOf("Robin", "robbie", "rob"), profile().addressingNames(listOf("robbie", " rob ")))
    }

    @Test
    fun `a name repeated in another case is kept once`() {
        assertEquals(listOf("Robin"), profile(username = "robinbot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robbie"), profile().addressingNames(listOf("ROBIN", "robbie", "Robbie")))
    }

    // a start that meets a telegram outage waits for it to pass instead of ending the process
    @Test
    fun `getMe is asked again while telegram answers with a server error`() = runBlocking {
        val client = GetMeClient(apiError(502, "Bad Gateway"))

        assertEquals(BotProfile(userId = 7, username = "robinbot", displayName = "Robin"), client.proxy.botProfile())
        assertEquals(2, client.calls)
    }

    @Test
    fun `a refused token is not waited out`() = runBlocking {
        val client = GetMeClient(apiError(401, "Unauthorized"))

        assertFailsWith<TelegramApiRequestException> { client.proxy.botProfile() }
        assertEquals(1, client.calls)
    }

    // fails the first getMe with [firstError], answers every later one
    private class GetMeClient(private val firstError: Throwable) {

        var calls = 0
            private set

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, _ ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                calls++

                if (calls == 1) {
                    CompletableFuture.failedFuture<Any>(firstError)
                } else {
                    CompletableFuture.completedFuture<Any>(
                        User.builder().id(7L).firstName("Robin").userName("robinbot").isBot(true).build(),
                    )
                }
            } as TelegramClient
    }

    private fun apiError(code: Int, description: String) =
        TelegramApiRequestException(
            "Error executing org.telegram.telegrambots.meta.api.methods.GetMe query",
            ApiResponse.builder<Serializable>().ok(false).errorCode(code).errorDescription(description).build(),
        )

    private fun profile(username: String? = "robinbot") =
        BotProfile(userId = 1, username = username, displayName = "Robin")
}
