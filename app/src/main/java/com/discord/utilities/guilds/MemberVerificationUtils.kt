package com.discord.utilities.guilds

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.api.guild.GuildFeature
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.widgets.servers.member_verification.MemberVerificationPendingDialog
import com.discord.widgets.servers.member_verification.MemberVerificationPendingViewModel
import com.discord.widgets.servers.member_verification.WidgetMemberVerification

/**
 * Membership screening ("rules gate") helpers.
 *
 * A gated guild comes in two shapes, and which one it is decides whether the user is a member at
 * all. [hasVerificationGate] alone means the guild screens people but still admits them, as a
 * member whose `pending` flag is set: the guild is in `StoreGuilds`, its channels are readable, and
 * only talking is blocked. [requiresManualApproval] additionally means the guild admits nobody
 * until a moderator says so, so there is no membership and no `GUILD_CREATE` — only an application.
 *
 * The reference calls the second shape "preview disabled" in places, which is a historical name
 * from when `PREVIEW_ENABLED` carried the distinction. It no longer does: the join flow branches on
 * `MEMBER_VERIFICATION_MANUAL_APPROVAL`, and `PREVIEW_ENABLED` survives only as a deep-link check.
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

    /**
     * Whether joining [guild] produces an application a moderator has to approve, rather than a
     * pending membership.
     *
     * `COMMUNITY` is deliberately not required here — unlike [hasVerificationGate] this is asked of
     * guilds the user is not in, whose feature list arrives on a partial record.
     */
    @JvmStatic
    fun requiresManualApproval(guild: Guild?): Boolean {
        val features = guild?.features ?: return false
        return GuildFeature.MEMBER_VERIFICATION_GATE_ENABLED in features &&
            GuildFeature.MEMBER_VERIFICATION_MANUAL_APPROVAL in features
    }

    /**
     * Routes a tap on a guild the user has applied to but not joined.
     *
     * @return whether the tap was handled. `false` means there is nothing screening this guild and
     *   the caller should open it as normal.
     */
    @JvmStatic
    fun maybeShowVerificationGate(
        context: Context,
        fragmentManager: FragmentManager,
        guildId: Long,
        location: String = "Guild List",
    ): Boolean {
        val request = StoreStream.getGuildJoinRequests().getGuildJoinRequest(guildId)
        return when (request?.applicationStatus) {
            ApplicationStatus.PENDING -> {
                MemberVerificationPendingDialog.show(
                    fragmentManager,
                    guildId,
                    MemberVerificationPendingViewModel.DialogState.PENDING,
                )
                true
            }
            ApplicationStatus.REJECTED -> {
                MemberVerificationPendingDialog.show(
                    fragmentManager,
                    guildId,
                    MemberVerificationPendingViewModel.DialogState.REJECTED,
                )
                true
            }
            // Approved and not yet acknowledged: the membership is real, so the guild opens and the
            // success dialog arrives on its own from the store.
            ApplicationStatus.APPROVED -> false
            // An application the user abandoned part-way, or none at all. Either way the form is
            // where they need to go, provided screening still applies to them.
            ApplicationStatus.STARTED, ApplicationStatus.UNKNOWN, null -> {
                if (!isScreened(guildId)) return false
                WidgetMemberVerification.create(context, guildId, location)
                true
            }
        }
    }

    /**
     * Whether membership screening still stands between this user and talking in [guildId].
     *
     * True for an applicant who is not in the guild at all, and for a member whose `pending` flag
     * is still set. False once the flag clears — a full member has been admitted, and screening a
     * guild does not mean screening everyone in it forever.
     */
    @JvmStatic
    fun isScreened(guildId: Long): Boolean {
        val guild = resolveGuild(guildId)
        if (!hasVerificationGate(guild) && !requiresManualApproval(guild)) return false
        val isMember = StoreStream.getGuilds().getGuild(guildId) != null
        if (!isMember) return true
        return StoreStream.getGuilds()
            .getMember(guildId, StoreStream.getUsers().getMe().id)?.pending == true
    }

    /**
     * The guild record to judge a gate against, which for an applicant is not in `StoreGuilds`.
     */
    private fun resolveGuild(guildId: Long): Guild? =
        StoreStream.getGuilds().getGuild(guildId)
            ?: StoreStream.getGuildJoinRequests().getPendingGuild(guildId)
}
