package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table

// what each person spent on a budget day. kept for a few weeks past the sharing window, since the number of
// people the day is split between is read from the recent days rather than from the allowlist.
object TokenUsageByUserTable : Table("token_usage_by_user") {

    // local date as `yyyy-MM-dd` in the budget timezone, matching TokenUsageTable.
    val day = varchar("day", 10)

    val platform = platformColumn()
    val userId = externalId("user_id")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")

    override val primaryKey = PrimaryKey(day, platform, userId)
}
