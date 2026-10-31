package com.helltar.vusan.agent.grouplog

import com.helltar.vusan.request.ChatRef
import java.time.LocalDate

/**
 * The bot's diary entries for a chat's closed days, which [GroupLogReader] gives for a window too wide to
 * quote. A port, so the transcript reads the diary without depending on its writer.
 */
fun interface RememberedDays {

    /** The entry for each day from [from] to [to] inclusive that has one. */
    suspend fun between(chat: ChatRef, from: LocalDate, to: LocalDate): Map<LocalDate, String>
}
