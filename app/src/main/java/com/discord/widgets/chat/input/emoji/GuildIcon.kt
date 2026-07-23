package com.discord.widgets.chat.input.emoji

import android.content.Context
import android.util.AttributeSet
import com.discord.models.guild.Guild
import com.facebook.drawee.view.SimpleDraweeView

class GuildIcon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SimpleDraweeView(context, attrs, defStyleAttr) {
    fun updateView(guild: Guild) {
        if (!guild.hasIcon()) return
        val size = mediaProxySize(layoutParams.width)
        setImageURI("https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.webp?size=$size")
    }

    private fun mediaProxySize(requested: Int) = MEDIA_PROXY_SIZES.first { it >= requested }

    private companion object {
        val MEDIA_PROXY_SIZES = intArrayOf(
            16, 20, 22, 24, 28, 32, 40, 44, 48, 56, 60, 64, 80, 96, 100, 128,
            160, 240, 256, 300, 320, 480, 512, 600, 640, 1024, 1280, 1536, 2048,
            3072, 4096,
        )
    }
}
