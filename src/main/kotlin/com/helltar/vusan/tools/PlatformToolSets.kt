package com.helltar.vusan.tools

import com.helltar.vusan.tools.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext

/**
 * The tools one messenger offers that no other can.
 *
 * Resending a file by the platform's stored id, or a sticker set learned from a chat, is one messenger's own
 * model, which the shared catalog cannot express. So the adapter is asked per turn (each set captures that
 * turn's context and outbox) and gates them on what the chat allows, the same gate that decides what it could
 * deliver. A deployment with nothing of its own returns nothing.
 */
fun interface PlatformToolSets {

    fun of(context: RequestContext, outbox: BotOutbox): List<ToolSet>
}
