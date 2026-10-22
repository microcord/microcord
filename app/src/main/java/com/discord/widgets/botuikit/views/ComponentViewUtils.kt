package com.discord.widgets.botuikit.views

import android.net.Uri
import android.view.View
import com.discord.api.botuikit.ComponentEmoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.emoji.toCodePoints
import com.discord.views.VitoImageView

object ComponentViewUtils {
    fun VitoImageView.setEmojiOrHide(componentEmoji: ComponentEmoji?, emojiAnimationsEnabled: Boolean) {
        if (componentEmoji == null) {
            visibility = View.GONE
            return
        }

        visibility = View.VISIBLE
        val uri = emojiUri(componentEmoji, emojiAnimationsEnabled) { codePoints ->
            // Bots send names with or without the FE0F variation selector; the artwork has one form.
            val bundled = listOf(codePoints, codePoints.replace("_fe0f", ""), "${codePoints}_fe0f")
                .firstOrNull { resources.getIdentifier("emoji_$it", "raw", context.packageName) != 0 }
            ModelEmojiUnicode.getImageUri(bundled ?: codePoints, context)
        }
        if (tag != uri) {
            tag = uri
            setImageURI(uri)
        }
    }

    /** Custom emoji come from the CDN; unicode emoji use the same bundled artwork as reactions. */
    internal fun emojiUri(
        componentEmoji: ComponentEmoji,
        emojiAnimationsEnabled: Boolean,
        bundledUri: (codePoints: String) -> String,
    ): Uri {
        val id = componentEmoji.id?.toLongOrNull()
        if (id != null) {
            val extension = if (emojiAnimationsEnabled && componentEmoji.animated == true) "gif" else "png"
            return Uri.parse("https://cdn.discordapp.com/emojis/$id.$extension?size=64")
        }
        return Uri.parse(bundledUri(componentEmoji.name.toCodePoints()))
    }
}
