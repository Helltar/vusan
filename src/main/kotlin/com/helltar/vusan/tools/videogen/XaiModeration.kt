package com.helltar.vusan.tools.videogen

import com.helltar.vusan.infra.HttpStatusException
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

// what a 400 from xai's imagine api says when moderation rejected the result: measured live on a video on
// 2026-10-21, billed in full
private const val MODERATED_CODE = "imagine:content-moderated"

/** Whether an Imagine request failed because xAI's moderation rejected what it made, rather than for a reason to report. */
internal fun HttpStatusException.isImagineModerated(): Boolean =
    status == HttpStatusCode.BadRequest.value &&
            runCatching { (Json.parseToJsonElement(body.orEmpty()).jsonObject["code"] as? JsonPrimitive)?.contentOrNull }.getOrNull() == MODERATED_CODE
