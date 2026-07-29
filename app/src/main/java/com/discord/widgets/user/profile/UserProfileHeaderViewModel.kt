package com.discord.widgets.user.profile

import androidx.annotation.MainThread
import com.discord.api.channel.Channel
import com.discord.api.channel.isMultiUserDM
import com.discord.api.premium.PremiumTier
import com.discord.api.user.UserProfile
import com.discord.app.AppViewModel
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.streams.StreamContext
import com.discord.widgets.user.presence.ModelRichPresence
import rx.Observable

class UserProfileHeaderViewModel @JvmOverloads constructor(
    private val userId: Long = ME,
    storeObservable: Observable<StoreState>? = null,
) : AppViewModel<UserProfileHeaderViewModel.ViewState>(ViewState.Uninitialized) {
    data class StoreState(
        val me: MeUser,
        val user: User,
        val userIdToGuildMemberMap: Map<Long, GuildMember>,
        val guildMembers: Collection<Map<Long, GuildMember>>,
        val richPresence: ModelRichPresence?,
        val streamContext: StreamContext?,
        val userProfile: UserProfile,
        val allowAnimatedEmojis: Boolean,
        val reducedMotionEnabled: Boolean,
        val channel: Channel?,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

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

    init {
        storeObservable?.computationLatest()?.ui(this)?.appSubscribe(
            UserProfileHeaderViewModel::class.java,
            onNext = ::handleStoreState,
        )
    }

    internal fun handleStoreState(storeState: StoreState) {
        updateViewState(mapStoreState(storeState, viewState))
    }

    @MainThread
    fun toggleAllowAnimationInReducedMotion() {
        val state = viewState as? ViewState.Loaded ?: return
        if (!state.reducedMotionEnabled) return
        updateViewState(state.copy(allowAnimationInReducedMotion = !state.allowAnimationInReducedMotion))
    }

    companion object {
        const val ME = -1L

        internal fun mapStoreState(storeState: StoreState, currentState: ViewState?): ViewState.Loaded {
            val userId = storeState.user.id
            val guildMember = storeState.userIdToGuildMemberMap[userId]
            val guildMembersForAka = if (storeState.channel?.isMultiUserDM() == true) {
                storeState.guildMembers.mapNotNull { it[userId] }
            } else {
                emptyList()
            }
            return ViewState.Loaded(
                user = storeState.user,
                banner = storeState.userProfile.user.banner?.value,
                bannerColorHex = storeState.userProfile.user.bannerColor?.value,
                guildMember = guildMember,
                guildMembersForAka = guildMembersForAka,
                presence = storeState.richPresence?.presence,
                streamContext = storeState.streamContext,
                userProfile = storeState.userProfile,
                isMeUserPremium = storeState.me.premiumTier != PremiumTier.NONE,
                isMeUserVerified = storeState.me.isVerified,
                allowAnimatedEmojis = storeState.allowAnimatedEmojis,
                showPresence = true,
                editable = false,
                reducedMotionEnabled = storeState.reducedMotionEnabled,
                allowAnimationInReducedMotion =
                    (currentState as? ViewState.Loaded)?.allowAnimationInReducedMotion ?: false,
                isMe = userId == storeState.me.id,
            )
        }
    }
}
