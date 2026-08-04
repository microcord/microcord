package com.discord.widgets.user.profile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import com.facebook.drawee.controller.BaseControllerListener
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.image.ImageInfo

class UserProfileHeaderView(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private val banner: SimpleDraweeView
    private val bannerEdit: CardView
    private val gifTag: ImageView
    private val avatar: UserAvatarPresenceView
    private val avatarDecoration: SimpleDraweeView
    private val avatarEdit: CardView
    private val aka: UserAkaView
    private val badges: RecyclerView
    private val customStatus: SimpleDraweeSpanTextView
    private val primaryName: UsernameView
    private val secondaryName: SimpleDraweeSpanTextView
    private val badgeAdapter = BadgeAdapter()
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
        avatarDecoration = SimpleDraweeView(context).apply {
            visibility = View.INVISIBLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        requireNotNull<FrameLayout>(avatar.findViewById(R.id.avatar_container)).addView(
            avatarDecoration,
            FrameLayout.LayoutParams(dp(DECORATION_SIZE_DP), dp(DECORATION_SIZE_DP), Gravity.CENTER),
        )
        avatarEdit = requireNotNull(findViewById(R.id.avatar_edit))
        aka = requireNotNull(findViewById(R.id.user_aka))
        badges = requireNotNull(findViewById(R.id.user_profile_header_badges_recycler))
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
        badges.layoutManager = GridLayoutManager(context, 3, RecyclerView.VERTICAL, true)
        badges.adapter = badgeAdapter
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
        configureAvatarDecoration(state)
        configureNames(state.user, state.guildMember, state)
        aka.visibility = if (state.showAkas) View.VISIBLE else View.GONE
        aka.configure(state.guildMembersForAka)
        badgeAdapter.items = badgesFor(state)
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

    private fun configureAvatarDecoration(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val url = state.avatarDecorationUrl
        if (url == null) {
            avatarDecoration.visibility = View.INVISIBLE
            MGImages.setImage(avatarDecoration, emptyList())
            return
        }
        avatarDecoration.visibility = View.INVISIBLE
        MGImages.setImage(
            avatarDecoration,
            listOf(url),
            width = DECORATION_ASSET_SIZE_PX,
            height = DECORATION_ASSET_SIZE_PX,
            useSmallCache = true,
            controllerListener = object : BaseControllerListener<ImageInfo>() {
                override fun onFinalImageSet(id: String?, imageInfo: ImageInfo?, animatable: android.graphics.drawable.Animatable?) {
                    avatarDecoration.visibility = if (imageInfo == null) View.INVISIBLE else View.VISIBLE
                }

                override fun onFailure(id: String?, throwable: Throwable?) {
                    avatarDecoration.visibility = View.INVISIBLE
                }
            },
            autoPlayAnimations = state.shouldAnimateBanner,
        )
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
        val secondary = accountName.takeUnless { it.toString() == primary.toString() }
        secondaryName.text = secondary
        secondaryName.visibility = if (secondary != null && !state.showAkas) View.VISIBLE else View.GONE
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

    private inner class BadgeAdapter : RecyclerView.Adapter<BadgeHolder>() {
        var items = emptyList<Badge>()
            set(value) { field = value; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = BadgeHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.user_profile_header_badge, parent, false) as SimpleDraweeView,
        )
        override fun onBindViewHolder(holder: BadgeHolder, position: Int) {
            val badge = items[position]
            if (badge.iconUrl != null) {
                MGImages.setImage(holder.image, badge.iconUrl, useSmallCache = true)
            } else {
                MGImages.cancelImageRequests(holder.image)
                holder.image.setActualImageResource(badge.icon)
            }
            holder.image.contentDescription = badge.text
            holder.image.setOnClickListener { onBadgeClick(badge) }
        }
    }

    private class BadgeHolder(val image: SimpleDraweeView) : RecyclerView.ViewHolder(image)

    companion object {
        internal const val DECORATION_SIZE_DP = 96
        private const val DECORATION_ASSET_SIZE_PX = 256
        private const val BANNER_ASPECT_RATIO = 300f / 105f
    }

}
