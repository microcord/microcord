package com.discord.widgets.chat.polls

import android.view.View
import com.discord.api.message.PollEmoji
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.emoji.toCodePoints
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.EmojiImages
import com.discord.views.VitoImageView
import kotlin.math.roundToInt

internal fun VitoImageView.setPollEmoji(
    emoji: MessageReactionEmoji?,
) {
    if (emoji == null) {
        visibility = View.GONE
        release()
        return
    }
    visibility = View.VISIBLE
    val animate = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled()
    val size = IconUtils.getMediaProxySize((24 * resources.displayMetrics.density).roundToInt())
    val uri = emoji.id?.toLongOrNull()?.let { id ->
        EmojiImages.customEmojiUrl(id, emoji.animated && animate, size)
    } ?: ModelEmojiUnicode.getImageUri(emoji.name.orEmpty().toCodePoints(), context)
    show(listOf(uri), autoPlay = animate)
}

internal fun VitoImageView.setPollEmoji(emoji: PollEmoji?) = setPollEmoji(
    emoji?.let { MessageReactionEmoji(it.id?.toString(), it.name, it.animated == true) },
)
