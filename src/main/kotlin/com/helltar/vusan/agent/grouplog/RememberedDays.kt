package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.request.ChatRef
import java.time.LocalDate

/**
 * What the bot wrote down about the closed days of a chat, its diary, which [GroupLogReader] gives
 * for a window too wide to quote. A port, so the transcript reads the diary without depending on the
 * package that writes it.
 */
fun interface RememberedDays {

    /** The entry for each day from [from] to [to], both included, that has one. */
    suspend fun between(chat: ChatRef, from: LocalDate, to: LocalDate): Map<LocalDate, String>
}
