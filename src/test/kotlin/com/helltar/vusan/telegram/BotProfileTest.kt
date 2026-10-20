package com.helltar.vusan.telegram

import java.io.Serializable
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.objects.ApiResponse
import org.telegram.telegrambots.meta.api.objects.chat.ChatFullInfo
import org.telegram.telegrambots.meta.api.objects.User
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException
import org.telegram.telegrambots.meta.generics.TelegramClient

class BotProfileTest {

    @Test
    fun `the profile name leads, followed by the handle without its bot suffix`() {
        assertEquals(listOf("Robin", "robin_helper"), profile("robin_helperBot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robo"), profile("robo_bot").addressingNames(emptyList()))
    }

    @Test
    fun `a premium handle is spoken without its suffix too`() {
        assertEquals(listOf("Robin", "Robo"), profile("RoboAgent", "robo_bot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "helper"), profile("helper_ai", "robinbot").addressingNames(emptyList()))
    }

    @Test
    fun `configured names replace the ones read off the handle`() {
        assertEquals(listOf("Robin", "robbie", "rob"), profile().addressingNames(listOf("robbie", " rob ")))
    }

    @Test
    fun `a name repeated in another case is kept once`() {
        assertEquals(listOf("Robin"), profile("robinbot").addressingNames(emptyList()))
        assertEquals(listOf("Robin", "robbie"), profile().addressingNames(listOf("ROBIN", "robbie", "Robbie")))
    }

    // a start that meets a telegram outage waits for it to pass instead of ending the process
    @Test
    fun `getMe is asked again while telegram answers with a server error`() = runBlocking {
        val client = GetMeClient(apiError(502, "Bad Gateway"))

        assertEquals(BotProfile(userId = 7, usernames = listOf("robinbot"), displayName = "Robin"), client.proxy.botProfile())
        assertEquals(3, client.calls)
    }

    // getMe answers with one handle only, so the rest come from getChat in the order the profile shows them
    @Test
    fun `every active handle is read, the one the profile shows first`() = runBlocking {
        val client = GetMeClient(firstError = null, activeUsernames = listOf("RobinAgent", "robinbot"))

        val profile = client.proxy.botProfile()

        assertEquals(listOf("RobinAgent", "robinbot"), profile.usernames)
        assertEquals("RobinAgent", profile.username)
    }

    @Test
    fun `a refused token is not waited out`() = runBlocking {
        val client = GetMeClient(apiError(401, "Unauthorized"))

        assertFailsWith<TelegramApiRequestException> { client.proxy.botProfile() }
        assertEquals(1, client.calls)
    }

    // fails the first call with [firstError] when there is one and answers every later one: getMe with
    // the bot itself, getChat with [activeUsernames]
    private class GetMeClient(
        private val firstError: Throwable?,
        private val activeUsernames: List<String>? = null,
    ) {

        var calls = 0
            private set

        val proxy: TelegramClient =
            Proxy.newProxyInstance(
                TelegramClient::class.java.classLoader,
                arrayOf(TelegramClient::class.java)
            ) { _, method, args ->
                check(method.name == "executeAsync") { "unexpected client call: ${method.name}" }
                calls++

                when {
                    calls == 1 && firstError != null -> CompletableFuture.failedFuture<Any>(firstError)
                    args[0] is GetChat ->
                        CompletableFuture.completedFuture<Any>(
                            ChatFullInfo.builder().id(7L).type("private").activeUsernames(activeUsernames).build(),
                        )
                    else ->
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

    private fun profile(vararg usernames: String = arrayOf("robinbot")) =
        BotProfile(userId = 1, usernames = usernames.toList(), displayName = "Robin")
}
