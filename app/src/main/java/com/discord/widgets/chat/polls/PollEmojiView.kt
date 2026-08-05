package com.discord.widgets.chat.polls

import android.view.View
import com.discord.api.message.PollEmoji
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.domain.emoji.toCodePoints
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.textprocessing.EmojiImages
import com.facebook.drawee.backends.pipeline.Fresco
import com.facebook.drawee.view.SimpleDraweeView
import kotlin.math.roundToInt

internal fun SimpleDraweeView.setPollEmoji(
    emoji: MessageReactionEmoji?,
) {
    if (emoji == null) {
        visibility = View.GONE
        controller = null
        return
    }
    visibility = View.VISIBLE
    val animate = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled()
    val size = IconUtils.getMediaProxySize((24 * resources.displayMetrics.density).roundToInt())
    val uri = emoji.id?.toLongOrNull()?.let { id ->
        EmojiImages.customEmojiUrl(id, emoji.animated && animate, size)
    } ?: ModelEmojiUnicode.getImageUri(emoji.name.orEmpty().toCodePoints(), context)
    controller = Fresco.newDraweeControllerBuilder()
        .setUri(uri)
        .setOldController(controller)
        .setAutoPlayAnimations(animate)
        .build()
}

internal fun SimpleDraweeView.setPollEmoji(emoji: PollEmoji?) = setPollEmoji(
    emoji?.let { MessageReactionEmoji(it.id?.toString(), it.name, it.animated == true) },
)
