package com.discord.widgets.guilds.join

import android.view.View
import android.widget.TextView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView

class ChannelViewHolder(itemView: View) : BaseChannelViewHolder(itemView) {
    override fun bind(data: ChannelItem) {
        val channelData = data as ChannelItem.ChannelData
        val welcome = channelData.welcomeChannel
        val customEmoji = itemView.findViewById<SimpleDraweeView>(R.id.guild_welcome_channel_emoji)
        val unicodeEmoji = itemView.findViewById<TextView>(R.id.guild_welcome_channel_unicode_emoji)
        val emojiUrl = welcome.emojiId?.let {
            ModelEmojiCustom.getImageUri(it, false, IconUtils.getMediaProxySize(customEmoji.layoutParams.width))
        }

        itemView.findViewById<TextView>(R.id.guild_welcome_channel_description).text = welcome.description
        itemView.findViewById<TextView>(R.id.guild_welcome_channel_name).text =
            StoreStream.getChannels().getChannel(welcome.channelId)?.getDisplayName().orEmpty()
        itemView.setOnClickListener {
            channelData.goToChannel(welcome.channelId, channelData.index)
            channelData.dismissSheet()
        }

        when {
            emojiUrl != null -> {
                MGImages.setImage(customEmoji, emojiUrl)
                customEmoji.visibility = View.VISIBLE
                unicodeEmoji.visibility = View.GONE
            }
            welcome.emojiName != null -> {
                customEmoji.visibility = View.GONE
                unicodeEmoji.visibility = View.VISIBLE
                unicodeEmoji.text = welcome.emojiName
            }
            else -> {
                customEmoji.visibility = View.VISIBLE
                unicodeEmoji.visibility = View.GONE
                MGImages.setImage(customEmoji, null)
            }
        }
    }
}
