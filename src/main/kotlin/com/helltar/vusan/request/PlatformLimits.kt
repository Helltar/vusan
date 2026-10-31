package com.helltar.vusan.request

/**
 * What the messenger lets a bot move, bounding every tool that sends or fetches a file. The numbers live here
 * alone; a second adapter would turn them into request-context facts for its platform, with no change under
 * `tools/`.
 */
object PlatformLimits {

    /** The most a bot may upload in one message. */
    const val UPLOAD_MB = 50

    /** The most the platform serves a bot of a file someone sent. */
    const val DOWNLOAD_MB = 20

    /** The most a picture may weigh to be shown as a photo rather than a file. */
    const val PHOTO_MB = 10
}
