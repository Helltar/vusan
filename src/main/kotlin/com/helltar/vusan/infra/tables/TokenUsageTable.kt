package com.helltar.vusan.infra.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

// one row per budget day — the whole bot's spend, including work that belongs to no user — so a restart
// resumes the day instead of handing out the budget twice. the per-person split is TokenUserSpendTable.
object TokenUsageTable : Table("token_usage") {

    // local date as `yyyy-MM-dd` in the budget timezone, the zone whose midnight resets the budget.
    val day = varchar("day", 10)

    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(day)
}
