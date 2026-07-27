package com.discord.widgets.chat.input.emoji

import android.content.Context
import android.util.AttributeSet
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.facebook.drawee.view.SimpleDraweeView

class GuildIcon @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : SimpleDraweeView(context, attrs, defStyleAttr) {
    fun updateView(guild: Guild) {
        if (!guild.hasIcon()) return
        val size = IconUtils.getMediaProxySize(layoutParams.width)
        setImageURI("https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.webp?size=$size")
    }
}
