package com.discord.views

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.discord.R
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.generic.RoundingParams

class GuildView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs, 0) {
    private val avatar: VitoImageView
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
            avatar.release()
        } else {
            val size = if (targetImageSize == -1) 0 else IconUtils.getMediaProxySize(targetImageSize)
            avatar.show(listOf(requestUrl), width = size, height = size)
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
