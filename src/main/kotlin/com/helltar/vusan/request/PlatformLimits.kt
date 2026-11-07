package com.helltar.vusan.request

/**
 * What the messenger lets a bot move, bounding every tool that sends or fetches a file. The numbers live here
 * alone, and one value is chosen at startup and handed to whatever needs it; a second adapter would turn them
 * into request-context facts for its platform, with no change under `tools/`.
 */
data class PlatformLimits(
    /** The most a bot may upload in one message. */
    val uploadMb: Int,
    /** The most the platform serves a bot of a file someone sent. */
    val downloadMb: Int,
) {

    val uploadBytes: Long
        get() = uploadMb * BYTES_PER_MB

    val downloadBytes: Long
        get() = downloadMb * BYTES_PER_MB

    companion object {

        /** The most a picture may weigh to be shown as a photo rather than a file, whichever server sends it. */
        const val PHOTO_MB = 10

        /** Telegram's own Bot API server. */
        val TELEGRAM = PlatformLimits(uploadMb = 50, downloadMb = 20)

        /**
         * A local Bot API server, which takes uploads up to 2000 MB and serves downloads of any size. What bounds
         * a file then is the bot itself: it holds every file it moves in memory whole, so both directions stop
         * well short of what the server would take.
         */
        val TELEGRAM_LOCAL_SERVER = PlatformLimits(uploadMb = 100, downloadMb = 100)

        private const val BYTES_PER_MB = 1024L * 1024
    }
}
