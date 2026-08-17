package com.discord.widgets.botuikit.views

import android.net.Uri
import android.view.View
import com.discord.api.botuikit.ComponentEmoji
import com.discord.models.domain.emoji.toCodePoints
import com.discord.views.VitoImageView

object ComponentViewUtils {
    fun VitoImageView.setEmojiOrHide(componentEmoji: ComponentEmoji?, emojiAnimationsEnabled: Boolean) {
        if (componentEmoji == null) {
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        val uri = emojiUri(componentEmoji, emojiAnimationsEnabled)
        if (tag != uri) {
            tag = uri
            setImageURI(uri)
        }
    }

    internal fun emojiUri(componentEmoji: ComponentEmoji, emojiAnimationsEnabled: Boolean): Uri {
        val id = componentEmoji.id?.toLongOrNull()
        if (id != null) {
            val extension = if (emojiAnimationsEnabled && componentEmoji.animated == true) "gif" else "png"
            return Uri.parse("https://cdn.discordapp.com/emojis/$id.$extension?size=64")
        }
        return Uri.parse(
            "https://twemoji.maxcdn.com/v/latest/72x72/${componentEmoji.name.toCodePoints("-")}.png",
        )
    }
}
