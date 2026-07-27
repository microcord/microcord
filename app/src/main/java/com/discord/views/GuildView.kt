package com.discord.views

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.generic.RoundingParams
import com.facebook.drawee.view.SimpleDraweeView
import com.facebook.imagepipeline.common.ResizeOptions
import com.facebook.imagepipeline.request.ImageRequestBuilder

class GuildView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val avatar: SimpleDraweeView
    private val guildText: TextView
    private var targetImageSize = -1
    private var textSize = -1

    init {
        LayoutInflater.from(context).inflate(R.layout.view_guild, this)
        avatar = requireNotNull(findViewById(R.id.guild_avatar))
        guildText = requireNotNull(findViewById(R.id.guild_text))
        clipToOutline = true
        setBackgroundResource(R.drawable.drawable_squircle_transparent)

        if (attrs != null) {
            context.obtainStyledAttributes(attrs, R.styleable.GuildView).run {
                try {
                    targetImageSize = getDimensionPixelSize(R.styleable.GuildView_gv_image_request_size, -1)
                    textSize = getDimensionPixelSize(R.styleable.GuildView_gv_text_size, -1)
                } finally {
                    recycle()
                }
            }
        }
        if (textSize != -1) guildText.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize.toFloat())
    }

    @JvmOverloads
    fun configure(iconUrl: String?, shortGuildName: String? = null) {
        val requestUrl = iconUrl ?: "asset:///images/default_icon_selected.jpg"
        if (requestUrl.isBlank()) {
            avatar.controller = null
        } else {
            val size = if (targetImageSize == -1) 0 else IconUtils.getMediaProxySize(targetImageSize)
            val request = ImageRequestBuilder.newBuilderWithSource(Uri.parse(requestUrl)).apply {
                if (size > 0) resizeOptions = ResizeOptions(size, size)
            }.build()
            avatar.controller = Fresco.newDraweeControllerBuilder()
                .setOldController(avatar.controller)
                .setImageRequest(request)
                .setAutoPlayAnimations(true)
                .build()
        }
        guildText.visibility = if (iconUrl == null) View.VISIBLE else View.GONE
        guildText.text = shortGuildName
    }

    fun useGuildIconCorners() {
        avatar.hierarchy.roundingParams = RoundingParams.fromCornersRadius(
            resources.getDimensionPixelSize(R.dimen.guild_icon_radius).toFloat(),
        )
    }
}
