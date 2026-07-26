package com.discord.api.handoff

/** Body of the request that trades a remote-auth key for a handoff token. */
data class CreateHandoffTokenRequest(
    val key: String,
) {
    override fun toString(): String = "CreateHandoffTokenRequest(key=<redacted>)"
}

/** A short-lived token that hands an authenticated session to another device. */
data class HandoffToken(
    val handoffToken: String?,
) {
    override fun toString(): String = "HandoffToken(handoffToken=<redacted>)"
}
