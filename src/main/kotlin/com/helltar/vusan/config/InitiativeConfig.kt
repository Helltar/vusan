package com.helltar.vusan.config

/**
 * Initiative: the bot looking over a group nobody called it into, now and then reacting to a message or
 * saying something.
 *
 * [intervalMinutes] is the middle of the pause between looks at one chat, each drawn at random around it so
 * the bot does not speak on a clock. [maxMessagesPerDay] bounds what it writes into one chat unprompted;
 * reactions have their own ceiling.
 *
 * None of these is a setting: a deployment only switches initiative on or off, and these defaults are what it
 * runs on.
 */
data class InitiativeConfig(
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val maxMessagesPerDay: Int = DEFAULT_MAX_MESSAGES_PER_DAY,
    val quietHours: QuietHours = QuietHours.DEFAULT,
) {

    init {
        require(intervalMinutes >= MIN_INTERVAL_MINUTES) {
            "The pause between two looks must be at least $MIN_INTERVAL_MINUTES minutes"
        }

        require(maxMessagesPerDay >= 0) { "Messages a day must not be negative" }
    }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 30
        const val DEFAULT_MAX_MESSAGES_PER_DAY = 4

        // a look is a model call over the recent lines; the floor keeps a typo from making the bot join every
        // exchange.
        const val MIN_INTERVAL_MINUTES = 5
    }
}

/**
 * The local hours the bot keeps to itself, [from] inclusive to [until] exclusive. A range may wrap midnight
 * (`23-7`); one starting where it ends is empty.
 */
data class QuietHours(val from: Int, val until: Int) {

    init {
        require(from in HOURS && until in HOURS) { "Quiet hours must lie within 0..23" }
    }

    operator fun contains(hour: Int): Boolean =
        if (from <= until) hour in from until until else hour !in until..<from

    companion object {
        // read by the constructor, so it must exist before the default below is built
        private val HOURS = 0..23

        val DEFAULT = QuietHours(from = 1, until = 8)
    }
}
