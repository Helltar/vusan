package com.helltar.vusan.config

/**
 * Initiative: the bot looking over a group nobody called it into, and now and then reacting to a
 * message or saying something of its own.
 *
 * [intervalMinutes] is the middle of the pause between two looks at one chat, each pause drawn at
 * random around it so the bot does not speak on a clock. [maxMessagesPerDay] bounds what it writes
 * into one chat unprompted; a reaction is not a message and has a ceiling of its own.
 *
 * None of the three is a setting. A deployment switches initiative on or off and nothing else: the
 * defaults here are the ones it runs on.
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

        // a look is a model call over the chat's recent lines, so the floor is what keeps a typo from
        // turning the bot into a participant of every exchange.
        const val MIN_INTERVAL_MINUTES = 5
    }
}

/**
 * The local hours the bot keeps to itself, from [from] up to but not including [until]. A range may run
 * over midnight (`23-7`), and one that starts where it ends is empty.
 */
data class QuietHours(val from: Int, val until: Int) {

    init {
        require(from in HOURS && until in HOURS) { "Quiet hours must lie within 0..23" }
    }

    operator fun contains(hour: Int): Boolean =
        if (from <= until) hour in from until until else hour >= from || hour < until

    companion object {
        // the constructor reads this, so it has to exist before the default below is built
        private val HOURS = 0..23

        val DEFAULT = QuietHours(from = 1, until = 8)
    }
}
