package com.discord.widgets.guilds.profile

import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.views.VitoImageView

class EmojiViewHolder(private val image: VitoImageView) : BaseEmojiViewHolder(image) {
    private val changeDetector = MGImages.DistinctChangeDetector()

    override fun bind(data: EmojiItem) {
        val emoji = (data as EmojiItem.EmojiData).emoji
        val size = IconUtils.getMediaProxySize(image.layoutParams.width)
        MGImages.setImage(image, emoji.imageUrl(size), width = size, height = size, changeDetector = changeDetector)
    }

    private fun Emoji.imageUrl(size: Int): String = getImageUri(true, size, image.context)
}
