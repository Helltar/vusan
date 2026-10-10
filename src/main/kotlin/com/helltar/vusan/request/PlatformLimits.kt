package com.helltar.vusan.request

/**
 * What the messenger this bot serves lets a bot move, which bounds every tool that sends a file or
 * fetches one. The numbers live here alone; a second adapter turns them into facts the request context
 * carries, read off the platform the turn came from, and nothing under `tools/` has to change for it.
 */
object PlatformLimits {

    /** The most a bot may upload in one message. */
    const val UPLOAD_MB = 50

    /** The most the platform serves a bot of a file somebody sent. */
    const val DOWNLOAD_MB = 20

    /** The most a picture may weigh to be shown as a photo rather than as a file. */
    const val PHOTO_MB = 10
}
