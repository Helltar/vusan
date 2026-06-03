package com.helltar.vusan.i18n

enum class Language(val codes: Set<String>) {
    ENGLISH(setOf("en")),
    UKRAINIAN(setOf("uk"));

    companion object {

        val DEFAULT = ENGLISH

        fun fromCode(code: String?): Language {
            val primary = code?.substringBefore('-')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return DEFAULT
            return entries.firstOrNull { primary in it.codes } ?: DEFAULT
        }
    }
}
