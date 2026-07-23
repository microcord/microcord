package com.discord.views.user

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.views.UsernameView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.drawable.ScalingUtils
import com.facebook.drawee.generic.GenericDraweeHierarchyBuilder
import com.facebook.drawee.generic.RoundingParams
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.facebook.drawee.span.SimpleDraweeSpanTextView
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder
import java.util.Locale

class SettingsMemberView constructor(
    context: Context,
    attrs: AttributeSet?,
) : ConstraintLayout(context, attrs) {
    private val largeAvatar: SimpleDraweeView
    private val memberSubtitle: SimpleDraweeSpanTextView
    private val memberTitle: UsernameView
    private var avatarBackgroundColor = themedColor(R.attr.colorBackgroundPrimary)
    private var user: User? = null
    private var guildMember: GuildMember? = null

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
        val nickname = guildMember?.nick
        val hasNickname = !nickname.isNullOrBlank()
        val hasGuildAvatar = guildMember?.hasAvatar() == true
        val avatarRequestSize = resources.getDimensionPixelSize(R.dimen.avatar_size_extra_large_account)
        setAvatar(largeAvatar, avatarUrl(user, guildMember, avatarRequestSize), true, avatarRequestSize)
        memberTitle.configureUsername(nickname ?: user.username)
        memberTitle.configureTag(
            user.isBot,
            if (user.isSystemUser) R.string.system_dm_tag_system else R.string.bot_tag_bot,
            (user.flags or user.publicFlags) and VERIFIED_BOT_FLAG == VERIFIED_BOT_FLAG ||
                user.isSystemUser || user.id == -1L,
        )
        val subtitle = DraweeSpanStringBuilder()
        if (hasGuildAvatar) {
            val inlineAvatarSize = resources.getDimensionPixelSize(R.dimen.avatar_size_huge)
            subtitle.setAvatar(
                avatarUrl(user, null, inlineAvatarSize, animate = false),
                inlineAvatarSize,
                avatarBackgroundColor,
            )
        }
        subtitle.append(formatUsername(user))
        memberSubtitle.setDraweeSpanStringBuilder(subtitle)
        memberSubtitle.visibility = if (hasGuildAvatar || hasNickname) View.VISIBLE else View.GONE
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
        val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url))
            .setResizeOptions(ResizeOptions(mediaProxySize(sizePx), mediaProxySize(sizePx)))
            .build()
        val controller = Fresco.newDraweeControllerBuilder()
            .setImageRequest(request)
            .setAutoPlayAnimations(false)
            .build()
        val hierarchy = GenericDraweeHierarchyBuilder(resources)
            .setPlaceholderImage(AppCompatResources.getDrawable(context, R.drawable.asset_default_avatar_32dp))
            .setRoundingParams(RoundingParams.asCircle().apply { setOverlayColor(overlayColor) })
            .setActualImageScaleType(ScalingUtils.ScaleType.CENTER_CROP)
            .build()
        setImageSpan(context, hierarchy, controller, start, start, sizePx, sizePx, false, 2)
    }

    private fun themedColor(attribute: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private companion object {
        const val VERIFIED_BOT_FLAG = 1 shl 16
    }
}

internal fun avatarUrl(user: User, guildMember: GuildMember?, size: Int? = null, animate: Boolean = true): String {
    val hash = guildMember?.takeIf(GuildMember::hasAvatar)?.avatarHash
    if (hash?.startsWith("data:") == true) return hash
    if (hash == null && user.avatar?.startsWith("data:") == true) return user.avatar!!
    val base = if (hash != null) {
        val extension = if (animate && hash.startsWith("a_")) "gif" else "webp"
        "https://cdn.discordapp.com/guilds/${guildMember.guildId}/users/${user.id}/avatars/$hash.$extension"
    } else if (user.avatar != null) {
        val extension = if (animate && user.avatar!!.startsWith("a_")) "gif" else "webp"
        "https://cdn.discordapp.com/avatars/${user.id}/${user.avatar}.$extension"
    } else {
        return "asset://asset/images/default_avatar_${user.discriminator % 5}.png"
    }
    return size?.let { "$base?size=${mediaProxySize(it)}" } ?: base
}

internal fun setAvatar(view: SimpleDraweeView, url: String, animate: Boolean, resizePx: Int? = null) {
    val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).apply {
        resizePx?.let { resizeOptions = ResizeOptions(it, it) }
    }.build()
    view.controller = Fresco.newDraweeControllerBuilder()
        .setOldController(view.controller)
        .setImageRequest(request)
        .setAutoPlayAnimations(animate)
        .build()
}

internal fun formatUsername(user: User): String =
    String.format(Locale.ROOT, "%s#%04d", user.username, user.discriminator)

internal fun mediaProxySize(requested: Int) = MEDIA_PROXY_SIZES.first { it >= requested }

private val MEDIA_PROXY_SIZES = intArrayOf(
    16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
    160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048, 3072, 4096,
)
