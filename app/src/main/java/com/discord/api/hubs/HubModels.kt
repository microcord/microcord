package com.discord.api.hubs

import com.discord.api.guild.Guild

/** A hub-eligible guild matched to the school email the user is verifying. */
data class GuildInfo(
    val icon: String?,
    val id: Long,
    val name: String,
)

/** Result of submitting a school email: which hubs it unlocks, if any. */
data class EmailVerification(
    val guildsInfo: List<GuildInfo>,
    val hasMatchingGuild: Boolean,
)

/** Result of redeeming an emailed hub verification code. */
data class EmailVerificationCode(
    val guild: Guild,
    val joined: Boolean,
)

/** A waitlist entry for a school that does not have a hub yet. */
data class WaitlistSignup(
    val email: String,
    val emailDomain: String,
    val school: String,
) {
    /**
     * Redacts [email]. The domain and school are kept: they are not personally identifying on their
     * own and they are what makes a failed signup diagnosable.
     */
    override fun toString(): String =
        "WaitlistSignup(email=REDACTED, emailDomain=$emailDomain, school=$school)"
}
