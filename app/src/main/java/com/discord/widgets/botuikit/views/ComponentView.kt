package com.discord.widgets.botuikit.views

import android.net.Uri
import android.view.View
import com.discord.api.botuikit.ComponentEmoji
import com.discord.api.botuikit.ComponentType
import com.discord.api.botuikit.SelectItem
import com.discord.models.botuikit.MessageComponent
import com.discord.models.domain.emoji.toCodePoints
import com.discord.widgets.botuikit.ComponentProvider
import com.facebook.drawee.view.SimpleDraweeView

interface ComponentView<T : MessageComponent> {
    fun configure(component: T, componentProvider: ComponentProvider, componentActionListener: ComponentActionListener)
    fun type(): ComponentType
}

interface ComponentActionListener {
    fun onButtonComponentClick(componentIndex: Int, customId: String)

    fun onSelectComponentClick(
        componentIndex: Int,
        customId: String,
        placeholder: String?,
        options: List<SelectItem>,
        selectedItems: List<SelectItem>,
        minOptionsToSelect: Int,
        maxOptionsToSelect: Int,
        emojiAnimationsEnabled: Boolean,
    )
}

internal fun View.setEnabledAlpha(enabled: Boolean, disabledAlpha: Float = 0.5f) {
    isEnabled = enabled
    alpha = if (enabled) 1f else disabledAlpha
}

internal fun SimpleDraweeView.setEmojiOrHide(emoji: ComponentEmoji?, animationsEnabled: Boolean) {
    if (emoji == null) {
        visibility = View.GONE
        setImageURI(null as Uri?)
        return
    }
    visibility = View.VISIBLE
    val uri = emojiUri(emoji, animationsEnabled)
    if (tag != uri) {
        tag = uri
        setImageURI(uri)
    }
}

internal fun emojiUri(emoji: ComponentEmoji, animationsEnabled: Boolean): Uri {
    val id = emoji.id?.toLongOrNull()
    if (id != null) {
        val extension = if (animationsEnabled && emoji.animated == true) "gif" else "png"
        return Uri.parse("https://cdn.discordapp.com/emojis/$id.$extension?size=64")
    }
    val codePoints = emoji.name.toCodePoints("-")
    return Uri.parse("https://twemoji.maxcdn.com/v/latest/72x72/$codePoints.png")
}
