package com.helltar.vusan.outbox

class BotOutbox(
    val chatId: Long = 0L,
    val userId: Long = 0L,
    val repliedPhoto: RepliedPhoto? = null
) {

    private val items = mutableListOf<BotOutput>()

    var redirectToPrivate: Boolean = false
        private set

    val pending: List<BotOutput>
        get() = items.toList()

    fun enqueue(item: BotOutput) {
        item.toPrivate = redirectToPrivate
        items += item
    }

    fun useDirectMessages() {
        redirectToPrivate = true
    }
}
