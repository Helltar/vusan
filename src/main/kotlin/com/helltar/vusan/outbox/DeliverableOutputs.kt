package com.helltar.vusan.outbox

import com.helltar.vusan.request.ChatCapabilities

/**
 * Whether this chat would accept [output] at all.
 *
 * `ToolRegistryFactory` already keeps a tool the chat would refuse out of the registry, but that is
 * not proof every queued output is deliverable: a text-first search tool queues photos, and the
 * workspace sends whatever files it was asked for, both through paths no capability gates. This is
 * the check that covers those, and any path written later.
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
    // words are what a chat that bans every kind of media still takes
    is BotOutput.Text, is BotOutput.RichMessage, is BotOutput.InlineChoice -> true
}

/** The name this kind goes by in a message to the model, matching `ChatCapabilities.restrictedKinds`. */
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
