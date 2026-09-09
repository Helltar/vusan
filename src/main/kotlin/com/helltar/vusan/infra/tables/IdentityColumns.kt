package com.helltar.vusan.infra.tables

import com.helltar.vusan.request.Platform
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table

// an external id is opaque text the platform issued, wide enough for every id shape in use — a
// numeric Telegram or Discord id, a Slack-style handle — without widening the keys it sits in.
private const val EXTERNAL_ID_CHARS = 64
private const val PLATFORM_NAME_CHARS = 16

/**
 * The platform half of an owner key. Every table holding state that belongs to somebody carries one,
 * because two messengers can issue the same id and neither may then read the other's rows.
 */
internal fun Table.platformColumn(name: String = "platform"): Column<Platform> =
    enumerationByName(name, PLATFORM_NAME_CHARS)

internal fun Table.externalId(name: String): Column<String> = varchar(name, EXTERNAL_ID_CHARS)
