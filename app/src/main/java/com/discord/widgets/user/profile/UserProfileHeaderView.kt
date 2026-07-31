package com.discord.widgets.user.profile

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.presence.PresenceUtils
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
        avatar.setOnAvatarBitmapLoadedListener { bitmap, _ ->
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            onAvatarRepresentativeColorUpdated(pixel)
        }
        avatar.setOnClickListener { if (state.editable) onAvatarEdit() }
        avatarEdit.setOnClickListener { if (state.editable) onAvatarEdit() }
        configureBanner(state)
        avatar.configure(UserAvatarPresenceView.ViewState(state.user, state.presence, state.showPresence, state.guildMember))
        configureNames(state.user, state.guildMember, state)
        aka.visibility = if (state.showAkas) View.VISIBLE else View.GONE
        aka.configure(state.guildMembersForAka)
        badgeAdapter.items = badgesFor(state)
        // Renders the custom-status emoji as an image span rather than its `:name:` shorthand,
        // and hides the row when the user has no status at all.
        PresenceUtils.setPresenceText(
            presence = state.presence,
            isStreamingApplication = state.streamContext != null,
            textView = customStatus,
        )
        avatarEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
        bannerEdit.visibility = if (state.editable) View.VISIBLE else View.GONE
    }

    fun updateBannerColor(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        runCatching { state.bannerColorHex?.let(Color::parseColor) }.getOrNull()?.let(banner::setBackgroundColor)
    }

    private fun configureBanner(state: UserProfileHeaderViewModel.ViewState.Loaded) {
        val hash = state.guildMember?.bannerHash ?: state.banner
        val size = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val url = when {
            hash == null -> null
            hash.startsWith("data:") -> hash
            state.guildMember?.hasBanner() == true -> IconUtils.getForGuildMemberBanner(
                hash,
                state.guildMember.guildId,
                state.user.id,
                size,
                state.shouldAnimateBanner,
            )
            else -> IconUtils.getForUserBanner(state.user.id, hash, size, state.shouldAnimateBanner)
        }
        banner.aspectRatio = if (url == null) 5f else 2.5f
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
        val primary = member?.nick?.takeUnless(String::isBlank) ?: user.username
        val discrim = user.discriminator.toString().padStart(4, '0')
        primaryName.configureUsername(if (discrim == "0000") primary else "$primary#$discrim")
        primaryName.configureTag(user.isBot, if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag_bot, false)
        val secondary = member?.nick?.let { "${user.username}#$discrim" }
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
            LayoutInflater.from(parent.context).inflate(R.layout.user_profile_header_badge, parent, false) as ImageView,
        )
        override fun onBindViewHolder(holder: BadgeHolder, position: Int) {
            val badge = items[position]
            holder.image.setImageResource(badge.icon)
            holder.image.contentDescription = badge.text
            holder.image.setOnClickListener { onBadgeClick(badge) }
        }
    }

    private class BadgeHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

}
