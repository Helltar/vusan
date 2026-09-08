package com.helltar.vusan.tools.sites

import kotlinx.serialization.Serializable

/**
 * The service states the caps an upload will be held to when it starts one, so the numbers live on the
 * side that enforces them. An operator raising a limit there needs no matching edit here.
 */
@Serializable
data class SiteLimits(
    val files: Int,
    val fileBytes: Long,
    val totalBytes: Long,
    val pathDepth: Int
)

@Serializable
data class UploadStarted(
    val uploadId: String,
    val expiresInMinutes: Int = 0,
    val limits: SiteLimits
)

@Serializable
data class UploadedFile(val path: String, val bytes: Long = 0)

@Serializable
data class SiteRecord(
    val owner: String,
    val label: String,
    val url: String,
    val files: Int = 0,
    val bytes: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class SiteFile(val path: String, val bytes: Long = 0)

@Serializable
data class SiteStatus(
    val published: Boolean,
    val url: String? = null,
    val files: Int = 0,
    val bytes: Long = 0,
    val updatedAt: Long = 0,
    // read off the site itself and capped by the service, which is why the count above can be larger.
    val listing: List<SiteFile> = emptyList(),
    val truncated: Boolean = false
)

@Serializable
data class SiteRemoved(val owner: String, val removed: Boolean)

@Serializable
data class SiteError(val error: String)
