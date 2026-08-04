package com.discord.widgets.chat.list.entries

import com.discord.api.application.Application
import com.discord.api.embeddedactivities.EmbeddedActivityPlatform
import com.discord.api.permission.Permission
import com.discord.api.user.NsfwAllowance
import com.discord.api.user.User
import com.discord.models.domain.ModelInvite
import com.discord.models.user.MeUser
import com.discord.utilities.permissions.PermissionUtils

data class ActivityMessageEntry(
    val messageId: Long,
    val channelId: Long,
    val guildId: Long,
    val application: Application?,
    val instanceId: String?,
    val inviteCode: String? = null,
    val inviter: User? = null,
    val invite: ModelInvite? = null,
) : ChatListEntry() {
    override val type = ACTIVITY_MESSAGE
    override val key = "$ACTIVITY_MESSAGE -- $messageId -- ${application?.id ?: 0L} -- ${inviteCode.orEmpty()}"
}

data class ActivityCardState(
    val action: Action,
    val reason: UnavailableReason? = null,
    val isEnded: Boolean = false,
) {
    enum class Action { START, JOIN, OPEN, UNAVAILABLE }
    enum class UnavailableReason { NO_APPLICATION, ENDED, PLATFORM, AGE, NITRO, PERMISSION }

    companion object {
        fun evaluate(
            entry: ActivityMessageEntry,
            me: MeUser,
            activeInstanceId: String?,
            participantIds: Set<Long>,
            permissions: Long?,
            now: Long = System.currentTimeMillis(),
        ): ActivityCardState {
            val application = entry.application
                ?: return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.NO_APPLICATION)
            if (entry.invite?.let { it.isRevoked() || it.isExpired(now) } == true) {
                return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.ENDED)
            }
            application.embeddedActivityConfig?.let { config ->
                if (EmbeddedActivityPlatform.ANDROID !in config.supportedPlatforms) {
                    return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.PLATFORM)
                }
                if (config.requiresAgeGate && me.nsfwAllowance != NsfwAllowance.ALLOWED) {
                    return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.AGE)
                }
                if ((config.premiumTierRequirement ?: 0) > me.premiumTier.ordinal) {
                    return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.NITRO)
                }
            }
            if (entry.guildId > 0L && !PermissionUtils.can(Permission.USE_EMBEDDED_ACTIVITIES, permissions)) {
                return ActivityCardState(Action.UNAVAILABLE, UnavailableReason.PERMISSION)
            }
            if (me.id in participantIds) return ActivityCardState(Action.OPEN)
            val hasInstance = activeInstanceId != null || entry.instanceId != null
            if (participantIds.isEmpty()) return ActivityCardState(Action.START, isEnded = hasInstance)
            return ActivityCardState(Action.JOIN)
        }
    }
}
