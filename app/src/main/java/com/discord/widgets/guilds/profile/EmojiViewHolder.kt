package com.discord.widgets.guilds.profile

import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView

class EmojiViewHolder(private val image: SimpleDraweeView) : BaseEmojiViewHolder(image) {
    override fun bind(data: EmojiItem) {
        val emoji = (data as EmojiItem.EmojiData).emoji
        MGImages.setImage(image, emoji.imageUrl(image))
    }

    private fun Emoji.imageUrl(view: SimpleDraweeView): String =
        getImageUri(true, IconUtils.getMediaProxySize(view.layoutParams.width), view.context)
}
