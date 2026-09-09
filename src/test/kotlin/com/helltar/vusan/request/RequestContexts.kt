package com.helltar.vusan.request

import com.helltar.vusan.i18n.Language

/**
 * An ordinary turn, for tests that need a context but are not about how one is built.
 *
 * Tests that *are* about identity or capabilities construct the record themselves, so that what they
 * assert stays visible in the test rather than in a default here.
 */
internal fun requestContext(
    chatId: Long = 1L,
    userId: Long = 1L,
    messageId: Long? = 1L,
    replyToMessageId: Long? = null,
    isPrivate: Boolean = true,
    attachedFiles: List<AttachedFile> = emptyList(),
    language: Language = Language.DEFAULT,
    capabilities: ChatCapabilities = ChatCapabilities.UNRESTRICTED
): RequestContext =
    RequestContext(
        chat = ChatContext(id = chatId, isPrivate = isPrivate, capabilities = capabilities),
        sender = SenderContext(id = userId),
        messageId = messageId,
        replyToMessageId = replyToMessageId,
        attachedFiles = attachedFiles,
        language = language
    )
