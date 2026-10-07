package com.helltar.vusan.i18n

enum class Language(val codes: Set<String>, private val cyrillic: Boolean = false) {

    ENGLISH(setOf("en")),
    UKRAINIAN(setOf("uk"), cyrillic = true),
    RUSSIAN(setOf("ru"), cyrillic = true),
    SPANISH(setOf("es"));

    companion object {
        val DEFAULT = ENGLISH

        // the letters only one of the two alphabets has, and the everyday words only one of the two
        // languages spells this way: enough to tell a line of chat apart without a language model.
        private const val UKRAINIAN_LETTERS = "іїєґ"
        private const val RUSSIAN_LETTERS = "ыэъё"
        private val UKRAINIAN_WORDS =
            setOf(
                "що", "шо", "це", "цей", "ця", "ще", "вже", "або", "але", "бо", "чи", "чому", "дякую", "треба",
                "нема", "зараз", "дуже", "теж", "також", "був", "була", "буде", "можна", "можу", "щось", "хтось",
                "ми", "ви", "вона", "вони", "мене", "його", "коли", "де", "як", "з",
            )
        private val RUSSIAN_WORDS =
            setOf(
                "что", "чё", "чо", "это", "спасибо", "или", "уже", "еще", "почему", "потому", "сейчас", "очень",
                "тоже", "также", "был", "была", "будет", "можно", "могу", "ничего", "если", "нет", "нету", "надо",
                "нужно", "только", "конечно", "вообще", "она", "они", "мне", "меня", "его", "их", "кто", "когда",
                "где", "как", "и", "с",
            )
        private val CYRILLIC_WORD = Regex("\\p{IsCyrillic}[\\p{IsCyrillic}']*")
        private const val SAMPLE_CHARS = 2000

        fun fromCode(code: String?): Language {
            val primary = code?.substringBefore('-')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return DEFAULT

            return entries.firstOrNull { primary in it.codes } ?: DEFAULT
        }

        /**
         * The language [text] is written in, as far as the text itself says. Ukrainian and Russian are told
         * apart by the letters only one of them has, then by the everyday words only one of them spells that
         * way. Text that settles nothing, such as Latin script or a bare `ок`, is left to [fallback], the
         * language the sender's client reports; a Cyrillic message whose fallback is not a Cyrillic language
         * is answered in Ukrainian rather than in English.
         */
        fun ofText(text: String?, fallback: Language): Language {
            val sample = text?.take(SAMPLE_CHARS)?.lowercase()?.takeIf { it.isNotBlank() } ?: return fallback
            val words = CYRILLIC_WORD.findAll(sample).map { it.value }.toList()
            val byLetters = pick(sample.count { it in UKRAINIAN_LETTERS }, sample.count { it in RUSSIAN_LETTERS })
            val byWords = pick(words.count { it in UKRAINIAN_WORDS }, words.count { it in RUSSIAN_WORDS })

            return when {
                byLetters != null -> byLetters
                byWords != null -> byWords
                words.isEmpty() -> fallback
                fallback.cyrillic -> fallback
                else -> UKRAINIAN
            }
        }

        private fun pick(ukrainian: Int, russian: Int): Language? =
            when {
                ukrainian > russian -> UKRAINIAN
                russian > ukrainian -> RUSSIAN
                else -> null
            }
    }
}
