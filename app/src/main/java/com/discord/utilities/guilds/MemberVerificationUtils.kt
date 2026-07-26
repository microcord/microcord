package com.discord.utilities.guilds

import com.discord.api.guild.GuildFeature
import com.discord.models.guild.Guild

/**
 * Membership screening ("rules gate") helpers.
 *
 * Reference: `com.discord.utilities.guilds.MemberVerificationUtils`. Only [hasVerificationGate] is
 * reconstructed — the object's other entry point, `maybeShowVerificationGate`, opens
 * `WidgetGuildJoinRequest` and reads `StoreGuildJoinRequests`, neither of which exists in this
 * project. Callers that would show the gate currently have nowhere to send the user; each one says
 * so at its call site.
 */
object MemberVerificationUtils {

    /**
     * Whether [guild] screens new members before letting them talk.
     *
     * Both features are required. `MEMBER_VERIFICATION_GATE_ENABLED` alone is not enough because a
     * guild can retain the flag after leaving the Community program, at which point the gate is
     * not actually enforced server-side.
     */
    @JvmStatic
    fun hasVerificationGate(guild: Guild?): Boolean {
        val features = guild?.features ?: return false
        return GuildFeature.MEMBER_VERIFICATION_GATE_ENABLED in features &&
            GuildFeature.COMMUNITY in features
    }
}
