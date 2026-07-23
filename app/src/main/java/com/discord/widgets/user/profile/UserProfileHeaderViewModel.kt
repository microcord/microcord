package com.discord.widgets.user.profile

import com.discord.api.user.UserProfile
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.utilities.streams.StreamContext

class UserProfileHeaderViewModel {
    sealed class ViewState {
        data class Loaded(
            val user: User,
            val banner: String?,
            val bannerColorHex: String?,
            val guildMember: GuildMember?,
            val guildMembersForAka: List<GuildMember>,
            val presence: Presence?,
            val streamContext: StreamContext?,
            val userProfile: UserProfile,
            val isMeUserPremium: Boolean,
            val isMeUserVerified: Boolean,
            val allowAnimatedEmojis: Boolean,
            val showPresence: Boolean,
            val editable: Boolean,
            val reducedMotionEnabled: Boolean,
            val allowAnimationInReducedMotion: Boolean,
            val isMe: Boolean,
        ) : ViewState() {
            val hasGuildMemberAvatar = guildMember?.hasAvatar() == true
            val hasNickname = !guildMember?.nick.isNullOrBlank()
            val showMediumAvatar = hasGuildMemberAvatar && !hasNickname
            val showSmallAvatar = hasGuildMemberAvatar && hasNickname
            val shouldAnimateBanner = !reducedMotionEnabled || allowAnimationInReducedMotion
            val shouldShowGIFTag = reducedMotionEnabled && !allowAnimationInReducedMotion && banner?.startsWith("a_") == true
            val isProfileLoaded = userProfile != UserProfile()
            val avatarColorId by lazy { user.avatar ?: user.discriminator.toString() }
            val guildMemberColorId by lazy { guildMember?.avatarHash ?: avatarColorId }
            val showAkas = guildMembersForAka.isNotEmpty() && !isMe
        }
    }
}
