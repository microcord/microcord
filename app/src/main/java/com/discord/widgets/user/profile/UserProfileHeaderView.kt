package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.discord.utilities.user.NicknameUtils
import com.discord.R
import com.discord.databinding.UserProfileHeaderViewBinding
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.views.user.UserAvatarPresenceView
import com.discord.widgets.user.Badge
import com.discord.widgets.media.WidgetMedia
import com.facebook.drawee.view.SimpleDraweeView

class UserProfileHeaderView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val binding = UserProfileHeaderViewBinding.inflate(LayoutInflater.from(context), this, true)
    private val bannerChangeDetector = MGImages.DistinctChangeDetector()
    private var backgroundColor: Int
    private var onAvatarEdit: () -> Unit = {}
    private var onBannerPress: () -> Unit = {}
    private var onBadgeClick: (Badge) -> Unit = {}
    private var onAvatarRepresentativeColorUpdated: (Int) -> Unit = {}

    /**
     * False when the host already paints the profile gradient behind this view. Filling our own
     * surface then lays a flat block over a gradient that keeps moving, which seams against
     * whatever sits below us.
     */
    var paintsProfileSurface = true

    init {
        backgroundColor = context.obtainStyledAttributes(attrs, intArrayOf(R.attr.user_profile_header_background_color)).run {
            try {
                getColor(0, ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary))
            } finally {
                recycle()
            }
        }
        setBackgroundColor(backgroundColor)
        binding.largeAvatar.setAvatarBackgroundColor(backgroundColor)
        binding.banner.setBackgroundColor(backgroundColor)
    }

    fun setOnAvatarEdit(callback: () -> Unit) { onAvatarEdit = callback }
    fun setOnBannerPress(callback: () -> Unit) { onBannerPress = callback }
    fun setOnBadgeClick(callback: (Badge) -> Unit) { onBadgeClick = callback }
    fun setOnAvatarRepresentativeColorUpdated(callback: (Int) -> Unit) { onAvatarRepresentativeColorUpdated = callback }

    fun updateViewState(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        configureTheme(state)
        // Owned here rather than by each host: the pill is part of this view, and three screens
        // render it, of which only one ever remembered to colour it.
        badgeRowColor = ProfileThemeColors.surfaceOverlay(
            state.profileThemeColors(isDarkTheme()),
            isDarkTheme(),
        )
        binding.largeAvatar.setOnAvatarBitmapLoadedListener { bitmap, _ ->
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            onAvatarRepresentativeColorUpdated(pixel)
        }
        binding.largeAvatar.setOnClickListener {
            if (state.editable) onAvatarEdit()
            else if (FeatureFlags.viewProfileImagesEnabled()) {
                IconUtils.getForGuildMemberOrUser(state.user, state.guildMember, IconUtils.AVATAR_MAX_SIZE_DP, true)
                    ?.let { WidgetMedia.launchImage(context, it, state.user.username) }
            }
        }
        binding.avatarEdit.setOnClickListener { if (state.editable) onAvatarEdit() }
        configureBanner(state)
        binding.largeAvatar.configure(UserAvatarPresenceView.ViewState(state.user, state.presence, state.showPresence, state.guildMember))
        binding.largeAvatar.setAvatarDecoration(state.avatarDecoration, animate = state.shouldAnimateBanner)
        configureNames(state.user, state.guildMember, state)
        binding.userProfileHeaderSecondaryName.setOnClickListener(
            if (!state.editable && state.showSmallAvatar && FeatureFlags.viewProfileImagesEnabled()) {
                View.OnClickListener {
                    IconUtils.getForUser(state.user, true, IconUtils.AVATAR_MAX_SIZE_DP)
                        ?.let { WidgetMedia.launchImage(context, it, state.user.username) }
                }
            } else null,
        )
        binding.userAka.visibility = if (state.showAkas) View.VISIBLE else View.GONE
        binding.userAka.configure(state.guildMembersForAka)
        setBadges(badgesFor(state))
        PresenceUtils.setCustomStatusText(state.presence, binding.userProfileHeaderCustomStatus)
        binding.avatarEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
        binding.bannerEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
        // Never over your own banner: the endpoint answers the owner in full, so a lock there would
        // only ever be a stale flag reading as "you are locked out of yourself".
        binding.privateProfileBanner.visibility =
            if (state.userProfile.isPrivate && !state.isMe) View.VISIBLE else View.GONE
    }

    fun updateBannerColor(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val colors = state.profileThemeColors(isDarkTheme())
        val color = when {
            colors == null -> runCatching { state.bannerColorHex?.let(Color::parseColor) }.getOrNull()
                ?: backgroundColor
            paintsProfileSurface -> colors.primary
            else -> Color.TRANSPARENT
        }
        binding.banner.setBackgroundColor(color)
    }

    private fun configureTheme(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val colors = state.profileThemeColors(isDarkTheme())
        val surface = colors?.headerSurface ?: backgroundColor
        setBackgroundColor(if (colors != null && !paintsProfileSurface) Color.TRANSPARENT else surface)
        binding.largeAvatar.setAvatarBackgroundColor(surface)
        binding.userProfileHeaderPrimaryName.setGuildTagProfileColors(colors?.tagPillSurface, colors?.textDefault)
        colors?.let {
            binding.userProfileHeaderPrimaryName.setUsernameColor(it.textDefault)
            binding.userProfileHeaderSecondaryName.setTextColor(it.textMuted)
            binding.userProfileHeaderCustomStatus.setTextColor(it.textDefault)
        } ?: run {
            binding.userProfileHeaderPrimaryName.setUsernameColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            binding.userProfileHeaderSecondaryName.setTextColor(ColorCompat.getThemedColor(context, R.attr.primary_400))
            binding.userProfileHeaderCustomStatus.setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun isDarkTheme() = StoreStream.getUserSettingsSystem().isDarkTheme()

    private fun bannerUrl(state: UserProfileHeaderViewModel.ViewState.Loaded, sizeDp: Int): String? {
        val hash = state.effectiveBanner ?: return null
        if (hash.startsWith("data:")) return hash
        return if (state.guildMember?.hasBanner() == true || state.effectiveBannerGuildId != null) {
            IconUtils.getForGuildMemberBanner(
                hash,
                state.effectiveBannerGuildId ?: checkNotNull(state.guildMember).guildId,
                state.user.id,
                sizeDp,
                state.shouldAnimateBanner,
            )
        } else {
            IconUtils.getForUserBanner(state.user.id, hash, sizeDp, state.shouldAnimateBanner)
        }
    }

    private fun configureBanner(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val url = bannerUrl(state, PROFILE_BANNER_SIZE_DP)
        binding.banner.aspectRatio = BANNER_ASPECT_RATIO
        MGImages.setImage(
            binding.banner,
            url,
            changeDetector = bannerChangeDetector,
            autoPlayAnimations = state.shouldAutoPlayBanner,
        )
        updateBannerColor(state)
        binding.banner.setOnClickListener {
            if (state.editable) onBannerPress()
            else if (url != null && FeatureFlags.viewProfileImagesEnabled()) {
                WidgetMedia.launchImage(context, bannerUrl(state, IconUtils.AVATAR_MAX_SIZE_DP) ?: url, state.user.username)
            }
        }
        binding.bannerEdit.setOnClickListener { onBannerPress() }
        binding.gifTag.visibility = if (state.shouldShowGIFTag) View.VISIBLE else View.GONE
    }

    private fun configureNames(user: User, member: GuildMember?, state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val accountName = user.getUserNameWithDiscriminator()
        // A guild member id short-circuits the lookup, so a member with no nickname stays on the
        // account display name rather than falling through to a friend nickname.
        val contextNickname = member?.nick
            ?: NicknameUtils.getNickname(member?.guildId, state.channelId, user.id)
        val displayName = user.getDisplayName(contextNickname)
        val primary = if (displayName == user.username) accountName else displayName
        binding.userProfileHeaderPrimaryName.configureUsername(primary)
        binding.userProfileHeaderPrimaryName.configureGuildTag(user.primaryGuild, 15f)
        binding.userProfileHeaderPrimaryName.configureTag(user.isBot, if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag_bot, false)
        // Reference: the username and pronouns share one line, joined by a divider dot.
        // The aka row already names the account, so only the pronouns survive alongside it.
        val accountLine = accountName
            .takeUnless { it.toString() == primary.toString() }
            ?.takeUnless { state.showAkas }
        val secondary = listOfNotNull(accountLine, state.pronouns)
            .takeIf(List<CharSequence>::isNotEmpty)
            ?.joinToString(DIVIDER_DOT)
        binding.userProfileHeaderSecondaryName.text = secondary
        binding.userProfileHeaderSecondaryName.visibility = if (secondary != null) View.VISIBLE else View.GONE
    }

    /**
     * Delegates to [Badge.getBadgesForUser], which owns the full badge matrix — tooltips, the
     * nine-tier boost icon, and the Nitro up-sell flags.
     */
    private fun badgesFor(state: UserProfileHeaderViewModel.ViewState.Loaded): List<Badge> =
        Badge.getBadgesForUser(
            user = state.user,
            profile = state.userProfile,
            isMeUserPremium = state.isMeUserPremium,
            isMeUserVerified = state.isMeUserVerified,
            context = context,
        )

    private fun setBadges(items: List<Badge>) {
        binding.userProfileHeaderBadges.removeAllViews()
        if (items.isEmpty()) {
            binding.userProfileHeaderBadges.background = null
            binding.userProfileHeaderBadges.setPadding(0, 0, 0, 0)
            return
        }
        binding.userProfileHeaderBadges.setPadding(dp(BADGE_ROW_PADDING_H_DP), dp(BADGE_ROW_PADDING_V_DP), dp(BADGE_ROW_PADDING_H_DP), dp(BADGE_ROW_PADDING_V_DP))
        binding.userProfileHeaderBadges.background = GradientDrawable().apply {
            cornerRadius = dp(BADGE_ROW_RADIUS_DP).toFloat()
            setColor(badgeRowColor)
        }
        val perRow = badgesPerRow()
        items.chunked(perRow).forEach { row ->
            binding.userProfileHeaderBadges.addView(badgeRow(row))
        }
    }

    private fun badgesPerRow(): Int {
        val available = (binding.userProfileHeaderBadges.parent as? View)?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val usable = available - dp(BADGE_ROW_PADDING_H_DP) * 2
        val step = dp(BADGE_SIZE_DP) + dp(BADGE_GAP_DP)
        return ((usable + dp(BADGE_GAP_DP)) / step).coerceAtLeast(1)
    }

    private fun badgeRow(items: List<Badge>): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = if (binding.userProfileHeaderBadges.childCount == 0) 0 else dp(BADGE_ROW_GAP_DP + BADGE_ROW_PADDING_V_DP * 2)
        }
        items.forEachIndexed { index, badge ->
            addView(badgeView(badge), LinearLayout.LayoutParams(dp(BADGE_SIZE_DP), dp(BADGE_SIZE_DP)).apply {
                if (index > 0) marginStart = dp(BADGE_GAP_DP)
            })
        }
    }

    private fun badgeView(badge: Badge): SimpleDraweeView = SimpleDraweeView(context).apply {
        if (badge.iconUrl != null) {
            MGImages.setImage(this, badge.iconUrl, useSmallCache = true)
        } else {
            setActualImageResource(badge.icon)
        }
        contentDescription = badge.text
        setOnClickListener { onBadgeClick(badge) }
    }

    /** Matches the content cards, which is where the reference takes this pill's colour from. */
    var badgeRowColor: Int = ColorCompat.getThemedColor(context, R.attr.colorBackgroundPrimary)
        set(value) {
            if (field == value) return
            field = value
            (binding.userProfileHeaderBadges.background as? GradientDrawable)?.setColor(value)
        }

    companion object {
        private const val DIVIDER_DOT = " ∙ "
        private const val BADGE_SIZE_DP = 20
        private const val BADGE_GAP_DP = 4
        private const val BADGE_ROW_GAP_DP = 8
        private const val BADGE_ROW_RADIUS_DP = 8
        private const val BADGE_ROW_PADDING_H_DP = 7
        private const val BADGE_ROW_PADDING_V_DP = 2
        private const val PROFILE_BANNER_SIZE_DP = 600
        private const val BANNER_ASPECT_RATIO = 300f / 105f
    }

}
