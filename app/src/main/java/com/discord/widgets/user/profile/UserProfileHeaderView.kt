package com.discord.widgets.user.profile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.user.NicknameUtils
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.UsernameView
import com.discord.views.user.UserAvatarPresenceView
import com.discord.widgets.channels.UserAkaView
import com.discord.widgets.user.Badge
import com.facebook.drawee.view.SimpleDraweeView

class UserProfileHeaderView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val banner: SimpleDraweeView
    private val bannerEdit: CardView
    private val gifTag: ImageView
    private val avatar: UserAvatarPresenceView
    private val avatarEdit: CardView
    private val aka: UserAkaView
    private val badges: LinearLayout
    private val customStatus: SimpleDraweeSpanTextView
    private val primaryName: UsernameView
    private val secondaryName: SimpleDraweeSpanTextView
    private val bannerChangeDetector = MGImages.DistinctChangeDetector()
    private var backgroundColor: Int
    private var onAvatarEdit: () -> Unit = {}
    private var onBannerPress: () -> Unit = {}
    private var onBadgeClick: (Badge) -> Unit = {}
    private var onAvatarRepresentativeColorUpdated: (Int) -> Unit = {}

    init {
        LayoutInflater.from(context).inflate(R.layout.user_profile_header_view, this)
        banner = requireNotNull(findViewById(R.id.banner))
        bannerEdit = requireNotNull(findViewById(R.id.banner_edit))
        gifTag = requireNotNull(findViewById(R.id.gif_tag))
        avatar = requireNotNull(findViewById(R.id.large_avatar))
        avatarEdit = requireNotNull(findViewById(R.id.avatar_edit))
        aka = requireNotNull(findViewById(R.id.user_aka))
        badges = requireNotNull(findViewById(R.id.user_profile_header_badges))
        customStatus = requireNotNull(findViewById(R.id.user_profile_header_custom_status))
        primaryName = requireNotNull(findViewById(R.id.user_profile_header_primary_name))
        secondaryName = requireNotNull(findViewById(R.id.user_profile_header_secondary_name))
        backgroundColor = context.obtainStyledAttributes(attrs, intArrayOf(R.attr.user_profile_header_background_color)).run {
            try {
                getColor(0, ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary))
            } finally {
                recycle()
            }
        }
        setBackgroundColor(backgroundColor)
        avatar.setAvatarBackgroundColor(backgroundColor)
        banner.setBackgroundColor(backgroundColor)
    }

    fun setOnAvatarEdit(callback: () -> Unit) { onAvatarEdit = callback }
    fun setOnBannerPress(callback: () -> Unit) { onBannerPress = callback }
    fun setOnBadgeClick(callback: (Badge) -> Unit) { onBadgeClick = callback }
    fun setOnAvatarRepresentativeColorUpdated(callback: (Int) -> Unit) { onAvatarRepresentativeColorUpdated = callback }

    fun updateViewState(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        configureTheme(state)
        avatar.setOnAvatarBitmapLoadedListener { bitmap, _ ->
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            onAvatarRepresentativeColorUpdated(pixel)
        }
        avatar.setOnClickListener { if (state.editable) onAvatarEdit() }
        avatarEdit.setOnClickListener { if (state.editable) onAvatarEdit() }
        configureBanner(state)
        avatar.configure(UserAvatarPresenceView.ViewState(state.user, state.presence, state.showPresence, state.guildMember))
        avatar.setAvatarDecoration(state.avatarDecoration, animate = state.shouldAnimateBanner)
        configureNames(state.user, state.guildMember, state)
        aka.visibility = if (state.showAkas) View.VISIBLE else View.GONE
        aka.configure(state.guildMembersForAka)
        setBadges(badgesFor(state))
        PresenceUtils.setCustomStatusText(state.presence, customStatus)
        avatarEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
        bannerEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
    }

    fun updateBannerColor(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val color = state.profileThemeColors(isDarkTheme())?.primary
            ?: runCatching { state.bannerColorHex?.let(Color::parseColor) }.getOrNull()
            ?: backgroundColor
        banner.setBackgroundColor(color)
    }

    private fun configureTheme(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val colors = state.profileThemeColors(isDarkTheme())
        val surface = colors?.headerSurface ?: backgroundColor
        setBackgroundColor(surface)
        avatar.setAvatarBackgroundColor(surface)
        colors?.let {
            primaryName.setUsernameColor(it.textDefault)
            secondaryName.setTextColor(it.textMuted)
            customStatus.setTextColor(it.textDefault)
        } ?: run {
            primaryName.setUsernameColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
            secondaryName.setTextColor(ColorCompat.getThemedColor(context, R.attr.primary_400))
            customStatus.setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun isDarkTheme() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun configureBanner(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val hash = state.effectiveBanner
        val size = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val url = when {
            hash == null -> null
            hash.startsWith("data:") -> hash
            state.guildMember?.hasBanner() == true || state.effectiveBannerGuildId != null -> IconUtils.getForGuildMemberBanner(
                hash,
                state.effectiveBannerGuildId ?: checkNotNull(state.guildMember).guildId,
                state.user.id,
                size,
                state.shouldAnimateBanner,
            )
            else -> IconUtils.getForUserBanner(state.user.id, hash, size, state.shouldAnimateBanner)
        }
        banner.aspectRatio = BANNER_ASPECT_RATIO
        MGImages.setImage(
            banner,
            url,
            changeDetector = bannerChangeDetector,
            autoPlayAnimations = state.shouldAutoPlayBanner,
        )
        updateBannerColor(state)
        banner.setOnClickListener { onBannerPress() }
        bannerEdit.setOnClickListener { onBannerPress() }
        gifTag.visibility = if (state.shouldShowGIFTag) View.VISIBLE else View.GONE
    }

    private fun configureNames(user: User, member: GuildMember?, state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val accountName = user.getUserNameWithDiscriminator()
        // A guild member id short-circuits the lookup, so a member with no nickname stays on the
        // account display name rather than falling through to a friend nickname.
        val contextNickname = member?.nick
            ?: NicknameUtils.getNickname(member?.guildId, state.channelId, user.id)
        val displayName = user.getDisplayName(contextNickname)
        val primary = if (displayName == user.username) accountName else displayName
        primaryName.configureUsername(primary)
        primaryName.configureTag(user.isBot, if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag_bot, false)
        // Reference: the username and pronouns share one line, joined by a divider dot.
        // The aka row already names the account, so only the pronouns survive alongside it.
        val accountLine = accountName
            .takeUnless { it.toString() == primary.toString() }
            ?.takeUnless { state.showAkas }
        val secondary = listOfNotNull(accountLine, state.pronouns)
            .takeIf(List<CharSequence>::isNotEmpty)
            ?.joinToString(DIVIDER_DOT)
        secondaryName.text = secondary
        secondaryName.visibility = if (secondary != null) View.VISIBLE else View.GONE
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

    /**
     * Badges wrap into pill rows rather than a plain strip: each row carries its own rounded
     * surface, so a short final row does not leave an empty tail behind it.
     */
    private fun setBadges(items: List<Badge>) {
        badges.removeAllViews()
        if (items.isEmpty()) return
        val perRow = badgesPerRow()
        items.chunked(perRow).forEach { row ->
            badges.addView(badgeRow(row))
        }
    }

    private fun badgesPerRow(): Int {
        val available = (badges.parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val usable = available / 2 - dp(BADGE_ROW_PADDING_H_DP) * 2
        val step = dp(BADGE_SIZE_DP) + dp(BADGE_GAP_DP)
        return ((usable + dp(BADGE_GAP_DP)) / step).coerceAtLeast(1)
    }

    private fun badgeRow(items: List<Badge>): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(BADGE_ROW_PADDING_H_DP), dp(BADGE_ROW_PADDING_V_DP), dp(BADGE_ROW_PADDING_H_DP), dp(BADGE_ROW_PADDING_V_DP))
        background = GradientDrawable().apply {
            cornerRadius = dp(BADGE_ROW_RADIUS_DP).toFloat()
            setColor(badgeRowColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = if (badges.childCount == 0) 0 else dp(BADGE_ROW_GAP_DP) }
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
            for (i in 0 until badges.childCount) {
                (badges.getChildAt(i).background as? GradientDrawable)?.setColor(value)
            }
        }

    companion object {
        private const val DIVIDER_DOT = " ∙ "
        private const val BADGE_SIZE_DP = 20
        private const val BADGE_GAP_DP = 4
        private const val BADGE_ROW_GAP_DP = 8
        private const val BADGE_ROW_RADIUS_DP = 8
        private const val BADGE_ROW_PADDING_H_DP = 7
        private const val BADGE_ROW_PADDING_V_DP = 2
        private const val BANNER_ASPECT_RATIO = 300f / 105f
    }

}
