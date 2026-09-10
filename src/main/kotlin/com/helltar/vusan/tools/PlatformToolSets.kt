package com.helltar.vusan.tools

import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.outbox.BotOutbox
import com.helltar.vusan.request.RequestContext

/**
 * The tools one messenger offers that no other one can.
 *
 * Resending a file by the id the platform already stores it under, or a sticker set the bot learned
 * from a chat, are not capabilities the shared registry can express: they are one messenger's own
 * model, and only its adapter knows whether it implements them at all. So the adapter is asked for
 * them per turn — each captures that turn's context and outbox — and gates them itself on what this
 * chat allows, since the same gate decides what the adapter could deliver anyway.
 *
 * A deployment with nothing of its own returns nothing; the shared tools do not change either way.
 */
fun interface PlatformToolSets {

    fun of(context: RequestContext, outbox: BotOutbox): List<ToolSet>
}
