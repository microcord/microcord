package com.discord.models.domain

/** Short-lived access token for a linked third-party account (Spotify, Twitch, …). */
data class ModelConnectionAccessToken(val accessToken: String) {
    override fun toString(): String = "ModelConnectionAccessToken(accessToken=<redacted>)"
}

/** Confirmation token returned when the user starts an email change. */
data class ModelEmailChangeConfirm(val token: String) {
    override fun toString(): String = "ModelEmailChangeConfirm(token=<redacted>)"
}

/** Handshake token that pairs this device with a desktop QR-code login. */
data class ModelRemoteAuthHandshake(val handshakeToken: String) {
    override fun toString(): String = "ModelRemoteAuthHandshake(handshakeToken=<redacted>)"
}

/** Preview thumbnail for a live application stream. */
data class ModelApplicationStreamPreview(val url: String)

/**
 * One RTC region plus the endpoint IPs used to measure latency against it.
 *
 * [ips] is nullable because the probe list is fetched from `latency.discord.media`, which is outside
 * the versioned API and has been seen to return entries without one; Gson would otherwise write a
 * null through the non-null type and the media engine would walk a missing array.
 */
data class ModelRtcLatencyRegion(
    val region: String,
    val ips: List<String>? = null,
)
