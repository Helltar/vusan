package com.helltar.vusan.outbox

import com.helltar.vusan.request.ChatCapabilities

/**
 * Whether this chat would accept [output] at all. `ToolCatalogFactory` keeps refused tools out of the
 * catalog, but a text-first search queues photos and the sandbox sends whatever it is asked, both ungated;
 * this covers those and any later path.
 */
internal fun ChatCapabilities.allows(output: BotOutput): Boolean = when (output) {
    is BotOutput.Photo, is BotOutput.PhotoGroup -> photos
    is BotOutput.Video -> videos
    is BotOutput.Audio, is BotOutput.AudioGroup -> audios
    is BotOutput.Document, is BotOutput.DocumentGroup -> documents
    is BotOutput.Voice -> voiceNotes
    is BotOutput.VideoNote -> videoNotes
    is BotOutput.Quiz, is BotOutput.Poll -> polls
    is BotOutput.Reaction -> reactions
    // telegram groups animations, games and stickers under one permission
    is BotOutput.Animation, is BotOutput.Sticker -> stickersAndAnimations
    // a chat banning every kind of media still takes words
    is BotOutput.Text, is BotOutput.RichMessage, is BotOutput.InlineChoice -> true
}

/** This kind's name in a message to the model, matching `ChatCapabilities.restrictedKinds`. */
internal fun BotOutput.kindName(): String = when (this) {
    is BotOutput.Photo, is BotOutput.PhotoGroup -> "photos"
    is BotOutput.Video -> "videos"
    is BotOutput.Audio, is BotOutput.AudioGroup -> "audio"
    is BotOutput.Document, is BotOutput.DocumentGroup -> "documents"
    is BotOutput.Voice -> "voice messages"
    is BotOutput.VideoNote -> "video notes"
    is BotOutput.Quiz, is BotOutput.Poll -> "polls"
    is BotOutput.Reaction -> "reactions"
    is BotOutput.Animation, is BotOutput.Sticker -> "stickers and GIFs"
    is BotOutput.Text, is BotOutput.RichMessage, is BotOutput.InlineChoice -> "text"
}
