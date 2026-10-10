package com.helltar.vusan.llm

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

// one field of a provider's JSON, or null when it is missing or not that shape: every client reads
// leniently, since a provider adds and reshapes fields faster than anyone follows

internal fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull
