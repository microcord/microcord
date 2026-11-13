package com.discord.widgets.chat.input.emoji

import com.discord.utilities.rest.ClientBackend
import android.content.Context
import android.util.AttributeSet
import com.discord.models.guild.Guild
import com.discord.utilities.icon.IconUtils
import com.discord.views.VitoImageView

class GuildIcon : VitoImageView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs)

    fun updateView(guild: Guild) {
        if (!guild.hasIcon()) return
        val size = IconUtils.getMediaProxySize(layoutParams.width)
        setImageURI("${ClientBackend.active.cdn}/icons/${guild.id}/${guild.icon}.webp?size=$size")
    }
}
