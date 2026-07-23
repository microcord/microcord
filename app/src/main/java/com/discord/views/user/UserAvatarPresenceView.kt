package com.discord.views.user

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.DrawableCompat
import com.discord.R
import com.discord.api.presence.ClientStatus
import com.discord.models.member.GuildMember
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.views.StatusView
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.request.BasePostprocessor
import com.facebook.imagepipeline.request.ImageRequestBuilder
import android.net.Uri
import kotlin.math.max

class UserAvatarPresenceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RelativeLayout(context, attrs) {
    data class ViewState(
        val user: User,
        val presence: Presence?,
        val showPresence: Boolean,
        val guildMember: GuildMember? = null,
    )

    private val avatar: SimpleDraweeView
    private val avatarCutout: ImageView
    private val staticAvatar: SimpleDraweeView
    private val status: StatusView
    private var cutoutSpacePx = 0
    private var onAvatarBitmapLoadedListener: (Bitmap, String?) -> Unit = { _, _ -> }

    init {
        LayoutInflater.from(context).inflate(R.layout.user_avatar_presence_view, this, true)
        avatar = requireNotNull(findViewById(R.id.avatar))
        avatarCutout = requireNotNull(findViewById(R.id.avatar_cutout))
        staticAvatar = requireNotNull(findViewById(R.id.static_avatar))
        status = requireNotNull(findViewById(R.id.user_avatar_presence_status))
        val attributes = intArrayOf(R.attr.user_avatar_background_color, R.attr.user_avatar_cutout_space)
        context.obtainStyledAttributes(attrs, attributes).run {
            try {
                setAvatarBackgroundColor(getColor(0, themedColor(R.attr.colorBackgroundTertiary)))
                cutoutSpacePx = getDimensionPixelSize(1, 0)
            } finally {
                recycle()
            }
        }
        avatar.clipToOutline = true
    }

    fun configure(viewState: ViewState) {
        val animationsEnabled = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
        val hash = viewState.guildMember?.takeIf(GuildMember::hasAvatar)?.avatarHash ?: viewState.user.avatar
        val animated = hash?.let {
            (it.startsWith("data:image/gif") || it.startsWith("a_")) && animationsEnabled
        } == true
        val url = avatarUrl(viewState.user, viewState.guildMember, animate = animationsEnabled)
        setAvatar(avatar, url, animated, true)
        if (animated) {
            setAvatar(staticAvatar, avatarUrl(viewState.user, viewState.guildMember, animate = false), false, true)
        }
        val presenceVisible = if (viewState.user.flags and INVISIBLE_STATUS_FLAG != 0) {
            viewState.presence?.status != null && viewState.presence.status != ClientStatus.OFFLINE
        } else {
            viewState.presence != null || viewState.showPresence
        }
        status.visibility = if (viewState.showPresence && presenceVisible) View.VISIBLE else View.GONE
        status.setPresence(viewState.presence)
    }

    fun setAvatarBackgroundColor(backgroundColor: Int) {
        status.setBackgroundColor(backgroundColor)
        DrawableCompat.setTint(avatarCutout.drawable.mutate(), backgroundColor)
    }

    fun setOnAvatarBitmapLoadedListener(listener: (Bitmap, String?) -> Unit) {
        onAvatarBitmapLoadedListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        avatarCutout.resize(size)
        avatar.resize(size - cutoutSpacePx * 2)
        var statusSize = size * 0.3375f
        var translation = size * 0.0375f
        var cornerRadius = size * 0.05f
        val density = resources.displayMetrics.density
        val minimumStatusSize = 12 * density
        if (statusSize < minimumStatusSize) {
            val scale = minimumStatusSize / statusSize
            statusSize *= scale
            translation *= scale
            cornerRadius *= scale
        }
        status.layoutParams = status.layoutParams.apply { width = statusSize.toInt() }
        status.translationX = translation - cutoutSpacePx
        status.translationY = translation - cutoutSpacePx
        status.setBorderWidth(max(translation, 2 * density).toInt())
        status.setCornerRadius(cornerRadius)
    }

    private fun setAvatar(view: SimpleDraweeView, url: String, animate: Boolean, notify: Boolean) {
        val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(url)).apply {
            if (notify) {
                postprocessor = object : BasePostprocessor() {
                    override fun process(bitmap: Bitmap) {
                        onAvatarBitmapLoadedListener(bitmap.copy(Bitmap.Config.ARGB_8888, false), url)
                    }
                }
            }
        }.build()
        view.controller = Fresco.newDraweeControllerBuilder()
            .setOldController(view.controller)
            .setImageRequest(request)
            .setAutoPlayAnimations(animate)
            .build()
    }

    private fun View.resize(size: Int) {
        layoutParams = layoutParams.apply {
            width = size
            height = size
        }
    }

    private fun themedColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attribute, value, true)
        return value.data
    }

    private companion object {
        const val INVISIBLE_STATUS_FLAG = 1 shl 19
    }
}
