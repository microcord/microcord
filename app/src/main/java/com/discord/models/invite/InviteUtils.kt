package com.discord.models.invite

import com.discord.models.domain.ModelInvite
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory

/** Builds the shareable `https://discord.gg/<code>` links shown in the invite sheet. */
object InviteUtils {

    /**
     * `BuildConfig.HOST_INVITE` in the reference build. The production flavour ships the short
     * invite host, which is also what [com.discord.utilities.intent.DeepLinkParser] recognises.
     */
    const val HOST_INVITE = "https://discord.gg"

    /** Reference `InviteQueue.enqueue` location: an invite link posted from the invite sheet. */
    const val INVITE_SEND_LOCATION = "Invite Action Sheet"

    fun createLinkFromCode(code: String, eventId: Long? = null): String = if (eventId != null) {
        String.format(ModelInvite.URL_FORMAT_WITH_EVENT, HOST_INVITE, code, eventId)
    } else {
        String.format(ModelInvite.URL_FORMAT, HOST_INVITE, code)
    }
}

/**
 * Whether this invite can still be used.
 *
 * Mirrors the reference comparison exactly, including its inverted expiry test: an invite is
 * treated as valid while its expiration timestamp is *below* the current time.
 */
fun ModelInvite.isValid(clock: Clock = ClockFactory.get()): Boolean =
    getExpirationTime() < clock.currentTimeMillis() && !isRevoked()
