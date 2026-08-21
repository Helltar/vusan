package com.helltar.vusan.tools.tgchannel

// invented English only: a fixture must never carry text from a real channel or a bug report.
internal fun channelPage(
    username: String = "example_channel",
    title: String = "Example Channel",
    posts: List<String>,
    moreBefore: Int? = null
): String =
    """
    <html>
      <head><meta property="og:title" content="$title"></head>
      <body>
        <div class="tgme_channel_info_header_title"><span>$title</span></div>
        ${posts.joinToString("\n")}
        ${moreBefore?.let { """<a href="/s/$username?before=$it" class="tme_messages_more js-messages_more" data-before="$it"></a>""" }.orEmpty()}
      </body>
    </html>
    """.trimIndent()

internal fun channelPost(
    id: Int,
    text: String = "",
    at: String = "2026-03-04T10:00:00+00:00",
    username: String = "example_channel",
    photos: List<String> = emptyList(),
    videoThumb: String? = null,
    reactions: List<Pair<String, String>> = emptyList(),
    replyQuote: String? = null,
    replyAuthor: String = "Example Channel",
    forwardedFrom: String? = null,
    linkPreview: Triple<String, String, String>? = null,
    views: String? = null
): String =
    buildString {
        appendLine("""<div class="tgme_widget_message" data-post="$username/$id">""")

        forwardedFrom?.let {
            appendLine("""<div class="tgme_widget_message_forwarded_from"><a class="tgme_widget_message_forwarded_from_name">$it</a></div>""")
        }

        // the quote of the replied-to post carries the same _text class and comes first in the DOM
        replyQuote?.let {
            appendLine(
                """<a class="tgme_widget_message_reply" href="https://t.me/$username/${id - 1}">""" +
                        """<span class="tgme_widget_message_author_name">$replyAuthor</span>""" +
                        """<div class="tgme_widget_message_text js-message_reply_text">$it</div></a>"""
            )
        }

        photos.forEach {
            appendLine("""<a class="tgme_widget_message_photo_wrap" style="background-image:url('$it')"></a>""")
        }

        videoThumb?.let {
            appendLine(
                """<a class="tgme_widget_message_video_player js-message_video_player">""" +
                        """<i class="tgme_widget_message_video_thumb" style="background-image:url('$it')"></i></a>"""
            )
        }

        if (text.isNotEmpty()) {
            appendLine("""<div class="tgme_widget_message_text js-message_text" dir="auto">$text</div>""")
        }

        linkPreview?.let { (site, previewTitle, description) ->
            appendLine(
                """<a class="tgme_widget_message_link_preview">""" +
                        """<div class="tgme_widget_message_link_preview_site_name">$site</div>""" +
                        """<div class="tgme_widget_message_link_preview_title">$previewTitle</div>""" +
                        """<div class="tgme_widget_message_link_preview_description">$description</div></a>"""
            )
        }

        if (reactions.isNotEmpty()) {
            append("""<div class="tgme_widget_message_reactions">""")
            reactions.forEach { (emoji, count) ->
                append("""<span class="tgme_reaction"><i class="emoji"><b>$emoji</b></i>$count</span>""")
            }
            appendLine("</div>")
        }

        views?.let { appendLine("""<span class="tgme_widget_message_views">$it</span>""") }

        appendLine(
            """<a class="tgme_widget_message_date" href="https://t.me/$username/$id">""" +
                    """<time datetime="$at"></time></a>"""
        )
        appendLine("</div>")
    }
