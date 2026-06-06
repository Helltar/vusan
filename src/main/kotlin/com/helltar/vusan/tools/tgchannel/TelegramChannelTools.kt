package com.helltar.vusan.tools.tgchannel

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.helltar.vusan.common.limitTo
import com.helltar.vusan.tools.common.suspendToolGuard

private const val MAX_POST_TEXT_CHARS = 800
private const val MAX_IMAGE_DESCRIPTION_CHARS = 600
private const val MAX_LINKS_PER_POST = 6
private const val MAX_IMAGES_TO_DESCRIBE = 4

@Suppress("unused")
class TelegramChannelTools(
    private val client: TelegramChannelClient,
    private val imageDescriber: TelegramChannelImageDescriber? = null
) : ToolSet {

    @Tool
    @LLMDescription(TelegramChannelToolDescriptions.READ_TELEGRAM_CHANNEL_POSTS)
    suspend fun readTelegramChannelPosts(
        @LLMDescription(TelegramChannelToolDescriptions.CHANNEL)
        channel: String,
        @LLMDescription(TelegramChannelToolDescriptions.MAX_POSTS)
        maxPosts: Int = 12,
        @LLMDescription(TelegramChannelToolDescriptions.DESCRIBE_IMAGES)
        describeImages: Boolean = true,
        @LLMDescription(TelegramChannelToolDescriptions.IMAGE_FOCUS)
        imageFocus: String = ""
    ): String = suspendToolGuard {
        val page = client.read(channel = channel, maxPosts = maxPosts)
        val imageDescriptions = if (describeImages) describePostImages(page, imageFocus) else emptyMap()

        if (page.posts.isEmpty()) {
            return@suspendToolGuard "No public posts found for @${page.username} at ${page.url}. " +
                    "The channel may be private, empty, age-restricted, unavailable, or blocked by Telegram web preview."
        }

        buildString {
            appendLine("Telegram channel @${page.username}: ${page.title}")
            appendLine("Source: ${page.url}")
            appendLine("Posts read: ${page.posts.size} recent post(s), newest first.")

            page.posts.forEachIndexed { index, post ->
                appendLine()
                append(index + 1)
                append(". Post ")
                appendLine(post.id)
                appendLine("URL: ${post.url}")
                post.datetime?.let { appendLine("Date: $it") }
                post.views?.let { appendLine("Views: $it") }
                appendLine("Media: ${if (post.hasMedia) "yes" else "no"}")
                appendLine("Text:")
                appendLine(post.text.ifBlank { "[media post without text]" }.trimPostText())

                imageDescriptions[post.id]?.let { descriptions ->
                    appendLine("Image descriptions:")
                    descriptions.forEachIndexed { imageIndex, description ->
                        appendLine("- Image ${imageIndex + 1}: ${description.limitTo(MAX_IMAGE_DESCRIPTION_CHARS)}")
                    }
                }

                val links = post.links.take(MAX_LINKS_PER_POST)

                if (links.isNotEmpty()) {
                    appendLine("Links:")
                    links.forEach { appendLine("- $it") }
                    if (post.links.size > MAX_LINKS_PER_POST) {
                        appendLine("- ...and ${post.links.size - MAX_LINKS_PER_POST} more")
                    }
                }
            }
        }.trim()
    }

    private suspend fun describePostImages(page: TelegramChannelPage, focus: String): Map<String, List<String>> {
        val describer = imageDescriber ?: return emptyMap()
        val result = linkedMapOf<String, MutableList<String>>()
        var remaining = MAX_IMAGES_TO_DESCRIBE

        for (post in page.posts) {
            if (remaining <= 0) break

            for (imageUrl in post.imageUrls) {
                if (remaining <= 0) break

                val description =
                    runCatching {
                        val image = client.downloadImage(imageUrl)
                        describer.describe(image, post, focus)
                    }.getOrElse { t -> "Could not inspect image: ${t.message ?: t::class.simpleName}" }

                result.getOrPut(post.id) { mutableListOf() } += description
                remaining--
            }
        }

        return result
    }

    private fun String.trimPostText(): String =
        trim().limitTo(MAX_POST_TEXT_CHARS)
}
