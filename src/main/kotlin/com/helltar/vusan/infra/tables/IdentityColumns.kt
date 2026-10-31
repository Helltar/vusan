package com.helltar.vusan.infra.tables

import com.helltar.vusan.request.Platform
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

// an external id is opaque platform-issued text, wide enough for every id shape in use (numeric telegram or
// discord ids, slack-style handles) without widening its keys. it holds references as well as owners: a
// message, a topic, a reply anchor.
private const val EXTERNAL_ID_CHARS = 64
private const val PLATFORM_NAME_CHARS = 16

/**
 * The platform half of an owner key. Every table holding someone's state carries one, since two messengers
 * can issue the same id and neither may read the other's rows.
 */
internal fun Table.platformColumn(name: String = "platform"): Column<Platform> =
    enumerationByName(name, PLATFORM_NAME_CHARS)

internal fun Table.externalId(name: String): Column<String> = varchar(name, EXTERNAL_ID_CHARS)
