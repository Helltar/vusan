package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// what each person spent on a budget day. kept for a few weeks past the sharing window, since the number of
// people the day is split between is read from the recent days rather than from the allowlist.
object TokenUserSpendTable : Table("token_user_spend") {

    // local date as `yyyy-MM-dd` in the budget timezone, matching TokenUsageTable.
    val day = varchar("day", 10)

    val userId = long("user_id")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(day, userId)
}
