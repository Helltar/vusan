package com.helltar.vusan.agent

import com.helltar.vusan.common.limitTo
import com.helltar.vusan.llm.ChatRequest
import com.helltar.vusan.llm.LlmClient
import com.helltar.vusan.llm.LlmModel
import com.helltar.vusan.llm.Message
import com.helltar.vusan.llm.Reply
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger("CompleteWithin")

/**
 * Completes [request] with a text answer of at most [maxChars] characters, for text the bot keeps and
 * reads again later. An answer that runs past it is handed back to the model to write again in
 * [askedChars] rather than cut: a cut drops whatever was written last, mid-sentence, and nothing that
 * reads the text later can tell. Only a rewrite that still runs past [maxChars] is cut, as a last
 * resort and with a warning, and an empty rewrite falls back to the first answer, cut.
 *
 * The reply returned is the last one made, its text replaced only when it had to be cut.
 */
internal suspend fun LlmClient.completeWithin(
    model: LlmModel,
    request: ChatRequest,
    askedChars: Int,
    maxChars: Int,
    what: String,
): Reply {
    val first = complete(model, request)
    val firstLength = first.message.text.trim().length

    if (firstLength <= maxChars) return first

    val rewrite =
        complete(
            model,
            request.copy(messages = request.messages + first.message + Message.User(rewriteRequest(firstLength, askedChars))),
        )
    val rewritten = rewrite.message.text.trim()

    if (rewritten.isNotEmpty() && rewritten.length <= maxChars) {
        log.info { "$what written again shorter: chars=[$firstLength] then [${rewritten.length}] max=[$maxChars]" }

        return rewrite
    }

    log.warn { "$what still over the limit when written again, cut: chars=[$firstLength] then [${rewritten.length}] max=[$maxChars]" }

    return rewritten.ifEmpty { first.message.text.trim() }
        .let { rewrite.copy(message = Message.Assistant(it.limitTo(maxChars))) }
}

private fun rewriteRequest(length: Int, askedChars: Int): String =
    "That is $length characters, more than the text can hold. Write it again in at most $askedChars characters: " +
            "keep what matters most and leave out the rest, rather than cutting the end off. Return only the text."
