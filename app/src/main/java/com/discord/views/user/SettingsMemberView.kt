package com.discord.views.user

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.images.setVitoSpan
import com.discord.views.ThemedColor
import com.discord.views.UsernameView
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.RoundingParams
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.VitoImageView
import com.facebook.drawee.span.DraweeSpanStringBuilder
import java.util.Locale
import com.discord.utilities.user.UserUtils
import com.discord.utilities.user.UserUtils.getDisplayName

class SettingsMemberView constructor(
    context: Context,
    attrs: AttributeSet?,
) : ConstraintLayout(context, attrs) {
    private val largeAvatar: VitoImageView
    private val memberSubtitle: SimpleDraweeSpanTextView
    private val memberTitle: UsernameView
    private var avatarBackgroundColor = ThemedColor.resolve(context, R.attr.colorBackgroundPrimary)
    /**
     * Whether to show the global identity under the display name. The member list wants it — a
     * per-guild avatar or nickname hides who someone actually is — but a list that only ever
     * identifies one person, like instant invites, reads as a duplicate avatar.
     */
    var showIdentitySubtitle: Boolean = true
        set(value) {
            field = value
            val configuredUser = user ?: return
            configure(configuredUser, guildMember)
        }

    private var user: User? = null
    private var guildMember: GuildMember? = null

    /** Skips re-issuing the avatar request when the row is rebound with the same user. */
    private val avatarChangeDetector = MGImages.DistinctChangeDetector()

    init {
        LayoutInflater.from(context).inflate(R.layout.settings_member_view, this)
        largeAvatar = requireNotNull(findViewById(R.id.large_avatar))
        memberSubtitle = requireNotNull(findViewById(R.id.member_subtitle))
        memberTitle = requireNotNull(findViewById(R.id.member_title))
        context.obtainStyledAttributes(attrs, intArrayOf(R.attr.smv_background_color)).run {
            try {
                avatarBackgroundColor = getColor(0, avatarBackgroundColor)
            } finally {
                recycle()
            }
        }
        setAvatarBackgroundColor(avatarBackgroundColor)
    }

    fun configure(user: User, guildMember: GuildMember?) {
        this.user = user
        this.guildMember = guildMember
        val nickname = guildMember?.identityNick()
        val hasNickname = !nickname.isNullOrBlank()
        val hasGuildAvatar = guildMember?.hasAvatar() == true
        val avatarRequestSize = resources.getDimensionPixelSize(R.dimen.avatar_size_large)
        IconUtils.setIcon(
            largeAvatar,
            avatarUrl(user, guildMember, avatarRequestSize),
            R.dimen.avatar_size_large,
            changeDetector = avatarChangeDetector,
        )
        memberTitle.configureUsername(user.getDisplayName(nickname))
        memberTitle.configureTag(
            user.isBot,
            if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag,
            (user.flags or user.publicFlags) and VERIFIED_BOT_FLAG == VERIFIED_BOT_FLAG ||
                user.isSystemUser || user.id == -1L,
        )
        val subtitle = DraweeSpanStringBuilder()
        if (hasGuildAvatar) {
            // The subtitle avatar sits inline with the username text, so it is the
            // small profile size rather than a full-size avatar.
            val inlineAvatarSize = resources.getDimensionPixelSize(R.dimen.avatar_size_profile_small)
            subtitle.setAvatar(
                avatarUrl(user, null, inlineAvatarSize, animate = false),
                inlineAvatarSize,
                avatarBackgroundColor,
            )
        }
        subtitle.append(formatUsername(user))
        memberSubtitle.setDraweeSpanStringBuilder(subtitle)
        memberSubtitle.visibility =
            if (showIdentitySubtitle && (hasGuildAvatar || hasNickname)) View.VISIBLE else View.GONE
    }

    fun setAvatarBackgroundColor(backgroundColor: Int) {
        avatarBackgroundColor = backgroundColor
        largeAvatar.hierarchy.roundingParams = RoundingParams.asCircle().apply {
            setOverlayColor(backgroundColor)
        }
        val configuredUser = user
        val configuredMember = guildMember
        if (configuredUser != null && configuredMember != null) configure(configuredUser, configuredMember)
    }

    private fun DraweeSpanStringBuilder.setAvatar(url: String, sizePx: Int, overlayColor: Int) {
        val start = length
        append('#')
        append('\u2002')
        setVitoSpan(
            context = context,
            url = url,
            start = start,
            end = start,
            width = sizePx,
            height = sizePx,
            alignment = 2,
            placeholder = AppCompatResources.getDrawable(context, R.drawable.asset_default_avatar_32dp),
            scaleType = ScalingUtils.ScaleType.CENTER_CROP,
            circular = true,
            background = ColorDrawable(overlayColor),
        )
    }

    private companion object {
        const val VERIFIED_BOT_FLAG = 1 shl 16
    }
}

/**
 * Avatar URL for a user, preferring their per-guild avatar.
 *
 * A locally picked avatar is already a `data:` URL and must be passed through
 * untouched; everything else is built by [IconUtils] so every screen shares the
 * same CDN cache entries.
 */
internal fun avatarUrl(user: User, guildMember: GuildMember?, size: Int? = null, animate: Boolean = true): String {
    val hash = guildMember?.takeIf(GuildMember::hasAvatar)?.avatarHash
    if (hash?.startsWith("data:") == true) return hash
    if (hash == null && user.avatar?.startsWith("data:") == true) return user.avatar!!
    return IconUtils.getForGuildMemberOrUser(user, guildMember, size, animate)
        ?: IconUtils.getDefaultAvatar(0)
}

/**
 * Binds an avatar URL through the shared image pipeline, so the request is
 * deduplicated, resized and rounded consistently with every other avatar.
 *
 * Animation is decided by the URL extension chosen in [avatarUrl] rather than by
 * the controller.
 */
internal fun setAvatar(
    view: VitoImageView,
    url: String,
    @Suppress("UNUSED_PARAMETER") animate: Boolean = true,
    resizePx: Int? = null,
) {
    val size = resizePx ?: 0
    IconUtils.setIcon(view, url, size, size)
}

internal fun formatUsername(user: User): String =
    user.username + UserUtils.discriminatorSuffix(user.discriminator)

internal fun mediaProxySize(requested: Int) = IconUtils.getMediaProxySize(requested)
